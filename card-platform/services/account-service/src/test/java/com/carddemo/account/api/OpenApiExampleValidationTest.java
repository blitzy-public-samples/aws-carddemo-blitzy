package com.carddemo.account.api;

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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs every published example of {@code src/main/resources/openapi.yaml} through the schema that
 * document declares for it, and runs a body violating each stated invariant through the same schema.
 *
 * <p>The document states invariants a reader relies on: every property of a read projection is
 * present, the account member of an update response is present and may be null, a submitted customer
 * identifier is nine digits, and no request carries a property the record does not declare. A schema
 * that describes those in prose and enforces none of them tells a caller a body is valid that the
 * service refuses, and a schema that refuses a body the service accepts is no better.
 *
 * <p>The validator is the one {@code libs/event-contracts} uses for the five event contracts, reading
 * JSON Schema Draft 2020-12. The whole {@code components} section is registered under one identifier,
 * so every {@code $ref} inside the document resolves the way a reader's tooling resolves it.
 *
 * <p>The refusal tests take a published example as their baseline and change one member. The example
 * itself is validated first, so a baseline that stopped being valid fails as its own assertion rather
 * than as a confusing refusal somewhere else.
 *
 * <p>No body below carries a Social Security Number or a government-issued identifier.
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
    private static final String DOCUMENT_URI = "https://carddemo.example/account-openapi";

    /** Any run of nine digits, which is the shape of a Social Security Number. */
    private static final Pattern NINE_DIGIT_RUN = Pattern.compile("(?<![0-9])[0-9]{9}(?![0-9])");

    /**
     * The nine-digit values a published body may carry.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} is nine digits wide, so a
     * customer identifier and a Social Security Number share a shape. Both values here are customer
     * identifiers of rows in {@code app/data/ASCII/custdata.txt}: row 50 for the bodies that show a
     * value, row 1 for the read miss. An identifier is what a caller sends and reads back, and the
     * Social Security Number of either row reaches no schema of this service.
     */
    private static final Set<String> PUBLISHED_CUSTOMER_IDS = Set.of("000000050", "000000001");

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
     * Asserts every example the document publishes validates against the schema declared beside it.
     *
     * <p>Twenty examples are validated: three request examples, being the update's two and the empty
     * body the cycle close reads; the eight the four operations declare inline beside a status; and
     * the nine the shared responses carry, one per shared answer. A response declared as a reference
     * to a shared response contributes its example once, where that response is defined.
     */
    @Test
    @DisplayName("every published example validates against its own schema")
    @SuppressWarnings("unchecked")
    void everyPublishedExampleValidates() {
        int validated = 0;

        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                Map<String, Object> declared = (Map<String, Object>) operation.getValue();
                String where = operation.getKey().toUpperCase(java.util.Locale.ROOT) + " "
                        + path.getKey();

                validated += validateExamplesOf((Map<String, Object>) declared.get("requestBody"),
                        where + " request");

                Map<String, Object> answers = (Map<String, Object>) declared.get("responses");
                for (Map.Entry<String, Object> answer : answers.entrySet()) {
                    Map<String, Object> response = (Map<String, Object>) answer.getValue();
                    if (response.containsKey("$ref")) {
                        continue;
                    }
                    validated += validateExamplesOf(response, where + " " + answer.getKey());
                }
            }
        }

        Map<String, Object> shared = (Map<String, Object>) node(
                (Map<String, Object>) openApi.get("components"), "responses");
        for (Map.Entry<String, Object> response : shared.entrySet()) {
            validated += validateExamplesOf((Map<String, Object>) response.getValue(),
                    "shared response " + response.getKey());
        }

        assertEquals(20, validated, "three request examples, eight declared beside an operation and"
                + " nine declared on a shared response");
    }

    /**
     * Asserts no published example carries a Social Security Number or a government identifier.
     *
     * <p>{@code CUST-SSN PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L17} recombines the three
     * submitted parts, so an example carrying three real parts publishes one. Every part of every
     * request example is a zero placeholder of the declared width, which the numeric edit
     * {@code 1245-EDIT-NUM-REQD} at {@code app/cbl/COACTUPC.cbl:L2109} refuses, so no reader can
     * mistake it for a value. Row 50 of {@code app/data/ASCII/custdata.txt} holds a Social Security
     * Number and a government identifier, and neither reaches this document.
     */
    @Test
    @DisplayName("no published example carries an identity document")
    @SuppressWarnings("unchecked")
    void noPublishedExampleCarriesAnIdentityDocument() {
        Map<String, Object> examples = (Map<String, Object>) node(requestBody(),
                "content", "application/json", "examples");

        for (Map.Entry<String, Object> example : examples.entrySet()) {
            Map<String, Object> customer = (Map<String, Object>) asMap(valueOf(example.getValue()))
                    .get("customerData");

            assertEquals("000", customer.get("socialSecurityPart1"),
                    example.getKey() + " publishes a placeholder area part");
            assertEquals("00", customer.get("socialSecurityPart2"),
                    example.getKey() + " publishes a placeholder group part");
            assertEquals("0000", customer.get("socialSecurityPart3"),
                    example.getKey() + " publishes a placeholder serial part");
            if (customer.containsKey("governmentIssuedId")) {
                assertEquals("0".repeat(20), customer.get("governmentIssuedId"),
                        example.getKey() + " publishes a placeholder government identifier");
            }
        }

        for (Map.Entry<String, String> body : everyPublishedBody().entrySet()) {
            Matcher found = NINE_DIGIT_RUN.matcher(body.getValue());
            while (found.find()) {
                assertTrue(PUBLISHED_CUSTOMER_IDS.contains(found.group()),
                        "every nine-digit run a published body carries is a customer identifier,"
                                + " never a recombined Social Security Number, and "
                                + found.group() + " in " + body.getKey() + " is neither");
            }
        }
    }

    /**
     * Renders every example the document publishes as the text a reader copies.
     *
     * <p>A description is prose and names an identifier where naming one is the point, so only the
     * example values are scanned.
     *
     * @return one rendered body per example, keyed by where the example sits
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> everyPublishedBody() {
        Map<String, String> bodies = new LinkedHashMap<>();

        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                Map<String, Object> declared = (Map<String, Object>) operation.getValue();
                String where = operation.getKey() + " " + path.getKey();
                collectBodies(bodies, (Map<String, Object>) declared.get("requestBody"),
                        where + " request");
                Map<String, Object> answers = (Map<String, Object>) declared.get("responses");
                for (Map.Entry<String, Object> answer : answers.entrySet()) {
                    collectBodies(bodies, (Map<String, Object>) answer.getValue(),
                            where + " " + answer.getKey());
                }
            }
        }
        Map<String, Object> shared = (Map<String, Object>) node(
                (Map<String, Object>) openApi.get("components"), "responses");
        for (Map.Entry<String, Object> response : shared.entrySet()) {
            collectBodies(bodies, (Map<String, Object>) response.getValue(),
                    "shared " + response.getKey());
        }
        return bodies;
    }

    /**
     * Renders the examples of one request body or response node into the collector.
     *
     * @param into  the collector
     * @param node  the request body or response node, which may be {@code null} or a reference
     * @param where what the node is
     */
    @SuppressWarnings("unchecked")
    private static void collectBodies(Map<String, String> into, Map<String, Object> node,
            String where) {

        if (node == null || node.containsKey("$ref")) {
            return;
        }
        Map<String, Object> content = (Map<String, Object>) node.get("content");
        if (content == null) {
            return;
        }
        for (Map.Entry<String, Object> media : content.entrySet()) {
            Map<String, Object> body = (Map<String, Object>) media.getValue();
            Map<String, Object> examples = (Map<String, Object>) body.get("examples");
            if (examples == null) {
                into.put(where, JSON.writeValueAsString(body.get("example")));
                continue;
            }
            examples.forEach((name, declared) ->
                    into.put(where + " " + name, JSON.writeValueAsString(valueOf(declared))));
        }
    }

    /**
     * Asserts the customer view refuses a body missing a property it declares required.
     *
     * <p>All eighteen columns of table {@code customer} are declared {@code NOT NULL} by
     * {@code src/main/resources/db/migration/V1__schema.sql}, and a record serializes every
     * component, so each of the sixteen properties is on the wire on every row. That includes
     * {@code middleName}, {@code addressLine2}, {@code phoneNumber2} and {@code eftAccountId},
     * which a reader might expect to be optional.
     */
    @Test
    @DisplayName("the customer view requires every property it serializes")
    void theCustomerViewRequiresEveryPropertyItSerializes() {
        Schema view = schemaFor("CustomerView");

        assertValid(view, customerView(), "the published customer view example");
        for (String property : List.of("middleName", "addressLine2", "phoneNumber2", "eftAccountId",
                "customerId", "ficoCreditScore", "primaryCardHolderIndicator")) {
            Map<String, Object> incomplete = customerView();
            incomplete.remove(property);
            assertInvalid(view, incomplete, "a customer view omitting " + property);

            Map<String, Object> nulled = customerView();
            nulled.put(property, null);
            assertInvalid(view, nulled, "a customer view sending null for " + property);
        }

        Map<String, Object> extra = customerView();
        extra.put("socialSecurityNumber", "0".repeat(9));
        assertInvalid(view, extra, "a customer view carrying the recombined column");
    }

    /**
     * Asserts the credit score of a read is bounded by the picture and not by the update edit.
     *
     * <p>Columns 330 to 332 of {@code app/data/ASCII/custdata.txt} hold scores from 1 to 793, and 21
     * of the 50 rows sit below 300. {@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
     * {@code app/cbl/COACTUPC.cbl:L848-L849} is reached from {@code 1275-EDIT-FICO-SCORE}, which only
     * {@code 1200-EDIT-MAP-INPUTS} performs. A read schema bounded at 300 declares 21 seeded rows
     * unrepresentable.
     */
    @Test
    @DisplayName("a read admits every score the column holds and no wider one")
    void aReadAdmitsEveryScoreTheColumnHolds() {
        Schema view = schemaFor("CustomerView");

        for (int score : List.of(0, 1, 274, 299, 300, 793, 850, 999)) {
            Map<String, Object> body = customerView();
            body.put("ficoCreditScore", score);
            assertValid(view, body, "a stored score of " + score);
        }
        for (int refused : List.of(-1, 1000)) {
            Map<String, Object> body = customerView();
            body.put("ficoCreditScore", refused);
            assertInvalid(view, body, "a score of " + refused + ", which PIC 9(03) cannot hold");
        }

        Map<String, Object> fractional = customerView();
        fractional.put("ficoCreditScore", 623.5);
        assertInvalid(view, fractional, "a score carrying a fraction");
    }

    /**
     * Asserts the update response requires both members and admits the null account it can carry.
     *
     * <p>{@code api/AccountController.answerOf} reads the account row again and passes {@code null}
     * when that read finds none. A schema referencing {@code AccountView} alone declares a body this
     * service can write invalid, and one leaving the member out of {@code required} tells a caller it
     * may be absent when it never is.
     */
    @Test
    @DisplayName("the update response requires both members and admits a null account")
    void theUpdateResponseRequiresBothMembersAndAdmitsANullAccount() {
        Schema response = schemaFor("AccountUpdateResponse");

        assertValid(response, updateResponse(), "the published applied example");

        Map<String, Object> vanished = updateResponse();
        vanished.put("account", null);
        assertValid(response, vanished, "a response whose second read found no row");

        Map<String, Object> absent = updateResponse();
        absent.remove("account");
        assertInvalid(response, absent, "a response omitting the account member");

        Map<String, Object> silent = updateResponse();
        silent.remove("message");
        assertInvalid(response, silent, "a response omitting the message member");

        Map<String, Object> empty = updateResponse();
        empty.put("message", "");
        assertInvalid(response, empty, "a response carrying an empty message");

        Map<String, Object> partial = updateResponse();
        Map<String, Object> account = new LinkedHashMap<>(asMap(partial.get("account")));
        account.remove("groupId");
        partial.put("account", account);
        assertInvalid(response, partial, "a response whose account omits a serialized column");
    }

    /**
     * Asserts the request schema refuses each body the service refuses.
     *
     * <p>{@code CUST-ID PIC 9(09)} at {@code app/cpy/CVCUS01Y.cpy:L5} is nine digits, and
     * {@code api/AccountController.updateAccount} refuses another shape before it reads a row.
     * {@code additionalProperties: false} on all three request schemas is enforced by
     * {@code config/RequestJsonStrictnessConfig}, and every property of all three is text, so a JSON
     * number in one of them is refused rather than coerced.
     */
    @Test
    @DisplayName("the request schema refuses what the service refuses")
    void theRequestSchemaRefusesWhatTheServiceRefuses() {
        Schema request = schemaFor("AccountUpdateRequest");

        assertValid(request, updateRequest(), "the published whole-screen example");

        for (String shape : List.of("1", "0000000012", "00000000A", "-00000001", "")) {
            assertInvalid(request, requestWithCustomerId(shape),
                    "a body whose customer identifier is '" + shape + "'");
        }
        assertValid(request, requestWithCustomerId("000000001"),
                "a body whose customer identifier is nine digits");

        Map<String, Object> named = updateRequest();
        named.put("accountId", "00000000050");
        assertInvalid(request, named, "a body naming the account the path already names");

        Map<String, Object> numeric = updateRequest();
        Map<String, Object> account = new LinkedHashMap<>(asMap(numeric.get("accountData")));
        account.put("creditLimit", 6169.00);
        numeric.put("accountData", account);
        assertInvalid(request, numeric, "a body sending a credit limit as a JSON number");

        Map<String, Object> unknown = updateRequest();
        Map<String, Object> block = new LinkedHashMap<>(asMap(unknown.get("accountData")));
        block.put("groupid", "");
        unknown.put("accountData", block);
        assertInvalid(request, unknown, "a body misspelling an optional account component");

        Map<String, Object> incomplete = updateRequest();
        Map<String, Object> customer = new LinkedHashMap<>(asMap(incomplete.get("customerData")));
        customer.remove("socialSecurityPart2");
        incomplete.put("customerData", customer);
        assertInvalid(request, incomplete, "a body dropping one of the three Social Security parts");

        Map<String, Object> accountOnly = updateRequest();
        accountOnly.remove("customerData");
        assertInvalid(request, accountOnly, "a body naming no customer at all");
    }

    /**
     * Builds a customer view body from the example the read operation publishes.
     *
     * @return a mutable copy of that example
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> customerView() {
        return new LinkedHashMap<>((Map<String, Object>) node(
                (Map<String, Object>) openApi.get("paths"), "/customers/{customerId}", "get",
                "responses", "200", "content", "application/json", "example"));
    }

    /**
     * Builds an update response body from the applied example the update operation publishes.
     *
     * @return a mutable copy of that example
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> updateResponse() {
        return new LinkedHashMap<>(asMap(valueOf(node(
                (Map<String, Object>) openApi.get("paths"), "/accounts/{accountId}", "put",
                "responses", "200", "content", "application/json", "examples", "applied"))));
    }

    /**
     * Builds an update request body from the whole-screen example the update operation publishes.
     *
     * @return a mutable copy of that example
     */
    private static Map<String, Object> updateRequest() {
        return new LinkedHashMap<>(asMap(valueOf(node(requestBody(),
                "content", "application/json", "examples", "wholeScreenSubmit"))));
    }

    /**
     * Builds an update request carrying one customer identifier.
     *
     * @param customerId the identifier to submit
     * @return the body
     */
    private static Map<String, Object> requestWithCustomerId(String customerId) {
        Map<String, Object> body = updateRequest();
        Map<String, Object> customer = new LinkedHashMap<>(asMap(body.get("customerData")));
        customer.put("customerId", customerId);
        body.put("customerData", customer);
        return body;
    }

    /**
     * Validates every example one request body or response node declares.
     *
     * @param node  the request body or response node, which may be {@code null}
     * @param where what the node is, for the failure message
     * @return the number of examples validated
     */
    @SuppressWarnings("unchecked")
    private static int validateExamplesOf(Map<String, Object> node, String where) {
        if (node == null) {
            return 0;
        }
        Map<String, Object> content = (Map<String, Object>) node.get("content");
        if (content == null) {
            return 0;
        }

        int validated = 0;
        for (Map.Entry<String, Object> media : content.entrySet()) {
            Map<String, Object> body = (Map<String, Object>) media.getValue();
            Schema schema = schemaOf((Map<String, Object>) body.get("schema"));
            Map<String, Object> examples = (Map<String, Object>) body.get("examples");

            if (examples == null) {
                assertTrue(body.containsKey("example"), where + " publishes an example");
                assertValid(schema, body.get("example"), where + " example");
                validated++;
                continue;
            }
            for (Map.Entry<String, Object> example : examples.entrySet()) {
                assertValid(schema, valueOf(example.getValue()),
                        where + " example " + example.getKey());
                validated++;
            }
        }
        return validated;
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
     * Compiles the schema one response or request declares, following a reference where it names one.
     *
     * @param declared the schema node of the document, either a reference or an inline schema
     * @return the compiled schema
     */
    private static Schema schemaOf(Map<String, Object> declared) {
        Object reference = declared.get("$ref");
        if (reference != null) {
            return schemaFor(nameOf(String.valueOf(reference)));
        }
        // The cycle-close request body declares its schema inline, because the body is always empty
        // and a component nothing else references would carry no meaning. Compiling the declared
        // node is what validates that example against the schema published beside it rather than
        // against a component it does not name.
        return registry.getSchema(JSON.writeValueAsString(declared), InputFormat.JSON);
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
     * Reads the request body node of the update operation.
     *
     * @return the request body node
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> requestBody() {
        return (Map<String, Object>) node((Map<String, Object>) openApi.get("paths"),
                "/accounts/{accountId}", "put", "requestBody");
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

    /**
     * @param value a parsed node expected to be a mapping
     * @return the node as a mapping, empty when it is not one
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }
}
