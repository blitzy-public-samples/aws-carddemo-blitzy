package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
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
 * document declares for it, and runs a body violating each stated invariant through the same schema.
 *
 * <p>The document states invariants a reader relies on: an approval carries no reject code, a decline
 * carries one paired with its own verbatim text, reject code {@code 0100} alone names no account, a
 * request names one identifier, and no request supplies a transaction identifier. A schema that
 * describes those in prose and enforces none of them tells a caller a body is valid that the service
 * refuses, and a schema that refuses a body the service accepts is no better.
 *
 * <p>The validator is the one {@code libs/event-contracts} uses for the five event contracts, reading
 * JSON Schema Draft 2020-12. The whole {@code components} section is registered under one identifier,
 * so every {@code $ref} inside the document resolves the way a reader's tooling resolves it.
 *
 * <p>No assertion here reads a fixture row, and no body below carries a card number a seeded row
 * holds.
 */
@DisplayName("Published schemas against published examples")
class OpenApiExampleValidationTest {

    /** The document, parsed once. */
    private static Map<String, Object> openApi;

    /** The registry every schema below is compiled through. */
    private static SchemaRegistry registry;

    /** Renders a body as the JavaScript Object Notation text the validator reads. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Identifier the document is registered under, so a local reference resolves against it. */
    private static final String DOCUMENT_URI = "https://carddemo.example/authorization-openapi";

    /**
     * The one request example the document publishes to be refused rather than sent.
     *
     * <p>{@code api/OpenApiContractTest} names the same example for the same reason, where it is the
     * one body required to name neither identifier.
     */
    private static final String REFUSAL_EXAMPLE = "validationFailure";

    /** Reads the document and registers its component section. */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readDocument() throws Exception {
        try (InputStream document = OpenApiExampleValidationTest.class
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
     * Asserts every request example the document publishes validates against the request schema,
     * apart from the one published to be refused.
     *
     * <p>{@value #REFUSAL_EXAMPLE} names neither identifier, which is the {@code WHEN OTHER} branch
     * of {@code app/cbl/COTRN02C.cbl:L224}, and the 422 answer publishes the body it produces. An
     * example of a refusable request is only worth publishing if it really is refusable, so it is
     * asserted invalid here rather than skipped: skipping it would let a later edit quietly make it
     * valid and leave the document showing a refusal that no longer happens.
     */
    @Test
    @DisplayName("every published request example validates, except the one published to be refused")
    @SuppressWarnings("unchecked")
    void everyPublishedRequestExampleValidates() {
        Schema request = schemaFor("AuthorizationRequest");
        Map<String, Object> examples = (Map<String, Object>) node(
                requestBody(), "content", "application/json", "examples");

        assertFalse(examples.isEmpty(), "the document publishes at least one request example");
        assertTrue(examples.containsKey(REFUSAL_EXAMPLE),
                "the document publishes the body its 422 answer comes from");
        examples.forEach((name, declared) -> {
            if (REFUSAL_EXAMPLE.equals(name)) {
                assertInvalid(request, valueOf(declared), "request example " + name);
                return;
            }
            assertValid(request, valueOf(declared), "request example " + name);
        });
    }

    /**
     * Asserts every response example validates against the schema its status declares.
     */
    @Test
    @DisplayName("every published response example validates")
    @SuppressWarnings("unchecked")
    void everyPublishedResponseExampleValidates() {
        Map<String, Object> responses = (Map<String, Object>) openApi.get("paths");
        Map<String, Object> answers = (Map<String, Object>) node(responses,
                "/authorizations", "post", "responses");

        int validated = 0;
        for (Map.Entry<String, Object> answer : answers.entrySet()) {
            Map<String, Object> declared = (Map<String, Object>) answer.getValue();
            Map<String, Object> content = (Map<String, Object>) declared.get("content");
            if (content == null) {
                continue;
            }
            for (Map.Entry<String, Object> media : content.entrySet()) {
                Map<String, Object> body = (Map<String, Object>) media.getValue();
                Schema schema = schemaOf((Map<String, Object>) body.get("schema"));

                Map<String, Object> examples = (Map<String, Object>) body.get("examples");
                if (examples == null) {
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
        // Eleven statuses carry a body, 403 documents one under each of its two media types, and
        // 422 carries five examples of its own, which is sixteen. 406 carries no body at all, so it
        // contributes none.
        assertEquals(16, validated, "every documented body carries an example that validates");
    }

    /**
     * Asserts the request schema refuses each body the binder refuses.
     *
     * <p>Each entry names one rule of {@code AuthorizationRequest} and one body breaking it. The
     * identifier rule is the {@code WHEN OTHER} branch at {@code app/cbl/COTRN02C.cbl:L224-L229}, the
     * emptiness rules are the eleven tests at {@code app/cbl/COTRN02C.cbl:L251-L320}, and the amount
     * grammar is the one {@code FUNCTION NUMVAL-C} reads.
     */
    @Test
    @DisplayName("the request schema refuses what the binder refuses")
    void theRequestSchemaRefusesWhatTheBinderRefuses() {
        Schema request = schemaFor("AuthorizationRequest");

        assertInvalid(request, requestWithout("cardNumber"), "a body naming neither identifier");
        assertInvalid(request, requestWith("cardNumber", null),
                "a body whose only identifier is an explicit null");
        assertInvalid(request, requestWith("transactionId", "0000000000683580"),
                "a body supplying a transaction identifier");
        assertInvalid(request, requestWith("source", "   "),
                "a body whose source holds spaces alone");
        assertInvalid(request, requestWith("merchantZip", ""),
                "a body whose merchant postal code is empty");
        assertInvalid(request, requestWith("amount", "++504.77"),
                "a body whose amount carries two signs");
        assertInvalid(request, requestWith("amount", "$504.77$"),
                "a body whose amount carries two currency signs");
        assertInvalid(request, requestWith("originTimestamp", "10/06/2022"),
                "a body whose capture moment is not a dated shape");

        assertValid(request, requestWith("accountId", null),
                "a body carrying a card and an explicit null account");
        assertValid(request, requestWith("transactionId", null),
                "a body sending an explicit null transaction identifier");
        assertValid(request, requestWith("originTimestamp", "2022-06-10"),
                "a body sending the ten-character capture date");
        assertValid(request, requestWith("processingTimestamp", "2022-06-10"),
                "a body sending the ten-character processing date");
        assertValid(request, requestWith("amount", "125.00CR"),
                "a body whose amount carries a credit marker");
    }

    /**
     * Asserts the two decision schemas enforce the invariants of their own status.
     *
     * <p>{@code app/cbl/CBTRN02C.cbl:L211} states an approval as a reject-code field holding zero, so
     * an approval carrying a code is not an outcome the source can produce. The four pairings come
     * from {@code app/cbl/CBTRN02C.cbl:L385-L419}, one {@code MOVE} of a code beside one
     * {@code MOVE} of its text. Reject code {@code 0100} follows the {@code INVALID KEY} branch at
     * {@code app/cbl/CBTRN02C.cbl:L383-L384}, which resolved no account of its own, so the account it
     * names is the one the caller declared; a body carrying that code and no account is a decision
     * nobody can attribute and the schema refuses it.
     */
    @Test
    @DisplayName("each decision schema enforces the invariants of its status")
    void eachDecisionSchemaEnforcesItsInvariants() {
        Schema approved = schemaFor("ApprovedAuthorization");
        Schema declined = schemaFor("DeclinedAuthorization");

        Map<String, Object> approval = decision(true, "00000000007", null, null);
        assertValid(approved, approval, "an approval naming its account and no reject code");
        assertInvalid(approved, decision(true, "00000000007", "0102", "OVERLIMIT TRANSACTION"),
                "an approval carrying a reject code");
        assertInvalid(approved, decision(true, null, null, null), "an approval naming no account");
        assertInvalid(approved, decision(false, "00000000007", null, null),
                "a body claiming 200 while approved is false");

        Map<String, Object> incomplete = new LinkedHashMap<>(approval);
        incomplete.remove("declineReasonDescription");
        assertInvalid(approved, incomplete, "a decision omitting a serialized member");

        assertValid(declined, decision(false, "00000000030", "0102", "OVERLIMIT TRANSACTION"),
                "a decline pairing 0102 with its own verbatim text");
        assertInvalid(declined,
                decision(false, "00000000030", "0102", "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"),
                "a decline pairing one reject code with the text of another");
        assertValid(declined, decision(false, "00000000030", "0100", "INVALID CARD NUMBER FOUND"),
                "a decline carrying 0100 and naming the account it was decided against");
        assertInvalid(declined, decision(false, null, "0100", "INVALID CARD NUMBER FOUND"),
                "a decline carrying 0100 and naming no account");
        assertInvalid(declined, decision(false, null, "0102", "OVERLIMIT TRANSACTION"),
                "a decline carrying 0102 and naming no account");
        assertInvalid(declined, decision(false, "00000000030", "0199", "OVERLIMIT TRANSACTION"),
                "a decline carrying a reject code the source never assigns");
        assertInvalid(declined, decision(true, "00000000030", "0102", "OVERLIMIT TRANSACTION"),
                "a body claiming 422 while approved is true");
    }

    /**
     * Builds one decision body carrying all five serialized members.
     *
     * @param approved    whether the platform authorized the transaction
     * @param accountId   the account, or {@code null} for the one outcome naming none
     * @param code        the reject code, or {@code null} on an approval
     * @param description the verbatim reject text, or {@code null} on an approval
     * @return the body
     */
    private static Map<String, Object> decision(boolean approved, String accountId, String code,
            String description) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionId", "0000001000000000");
        body.put("accountId", accountId);
        body.put("approved", approved);
        body.put("declineReasonCode", code);
        body.put("declineReasonDescription", description);
        return body;
    }

    /**
     * Builds a valid request body carrying one changed member.
     *
     * @param property the member to change
     * @param value    the value to set, which may be {@code null}
     * @return the body
     */
    private static Map<String, Object> requestWith(String property, Object value) {
        Map<String, Object> body = validRequest();
        body.put(property, value);
        return body;
    }

    /**
     * Builds a valid request body with one member dropped.
     *
     * @param property the member to drop
     * @return the body
     */
    private static Map<String, Object> requestWithout(String property) {
        Map<String, Object> body = validRequest();
        body.remove(property);
        return body;
    }

    /**
     * Builds a request body every constraint accepts.
     *
     * <p>The card number is a placeholder of sixteen zero digits, which no row of
     * {@code app/data/ASCII/cardxref.txt} carries.
     *
     * @return the body
     */
    private static Map<String, Object> validRequest() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transactionTypeCode", "01");
        body.put("transactionCategoryCode", "0001");
        body.put("source", "POS TERM");
        body.put("description", "Purchase at Abshire-Lowe");
        body.put("amount", "504.77");
        body.put("merchantId", "800000000");
        body.put("merchantName", "Abshire-Lowe");
        body.put("merchantCity", "North Enoshaven");
        body.put("merchantZip", "72112");
        body.put("cardNumber", "0000000000000000");
        body.put("originTimestamp", "2022-06-10 19:27:53.000000");
        body.put("processingTimestamp", "2022-06-10-19.27.53.410000");
        return body;
    }

    /**
     * Asserts one body validates against one schema.
     *
     * @param schema      the schema
     * @param body        the body
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
     * @param schema      the schema
     * @param body        the body
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
     * @param body   the body
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
     * Compiles the schema one response or request declares, following a reference or a choice.
     *
     * @param declared the schema node of the document
     * @return the compiled schema
     */
    @SuppressWarnings("unchecked")
    private static Schema schemaOf(Map<String, Object> declared) {
        if (declared.containsKey("$ref")) {
            return schemaFor(nameOf(String.valueOf(declared.get("$ref"))));
        }
        List<Map<String, Object>> alternatives =
                (List<Map<String, Object>>) declared.get("oneOf");
        StringBuilder choice = new StringBuilder("{\"oneOf\":[");
        for (int at = 0; at < alternatives.size(); at++) {
            if (at > 0) {
                choice.append(',');
            }
            choice.append("{\"$ref\":\"").append(DOCUMENT_URI).append('#')
                    .append(String.valueOf(alternatives.get(at).get("$ref")).substring(1))
                    .append("\"}");
        }
        return registry.getSchema(choice.append("]}").toString(), InputFormat.JSON);
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
     * Reads the request body node of the one operation.
     *
     * @return the request body node
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestBody() {
        return (Map<String, Object>) node((Map<String, Object>) openApi.get("paths"),
                "/authorizations", "post", "requestBody");
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
