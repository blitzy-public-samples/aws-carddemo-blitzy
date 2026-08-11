package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
 * <p>The document states invariants a reader relies on: every member of an update outcome is present,
 * the null members of an outcome are null rather than absent, the expiry year lies between 1950 and
 * 2099, the paging cursor is present and may be null, and the account filter refuses eleven zeros. A
 * schema that describes those in prose and enforces none of them tells a caller a body is valid that
 * the service refuses, and a schema that refuses a body the service accepts is no better.
 *
 * <p>The validator is the one {@code libs/event-contracts} uses for the five event contracts, reading
 * JSON Schema Draft 2020-12. The whole {@code components} section is registered under one identifier,
 * so every {@code $ref} inside the document resolves the way a reader's tooling resolves it.
 *
 * <p>The refusal tests take a published example as their baseline and change one member. The example
 * itself is validated first, so a baseline that stopped being valid fails as its own assertion rather
 * than as a confusing refusal somewhere else.
 *
 * <p>No body below carries a card number of a seeded row.
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
    private static final String DOCUMENT_URI = "https://carddemo.example/card-openapi";

    /** Keys of a route block that are operations rather than shared declarations. */
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** Any run of sixteen digits, which is the shape of a card number. */
    private static final Pattern SIXTEEN_DIGIT_RUN =
            Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])");

    /**
     * The sixteen-digit value a published body may carry.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is sixteen digits wide. This
     * value carries none of the fifty card numbers of {@code app/data/ASCII/carddata.txt}, so a
     * reader cannot mistake it for one, and it passes the shape
     * {@code app/cbl/COCRDUPC.cbl:L784} tests so the document's own example is a valid request.
     *
     * <p>It is the value the authorization contract publishes as well, and
     * {@code com.carddemo.equivalence.CardholderExampleContractTest} requires every guide and every
     * contract of the platform to carry one of the two synthetic numbers it declares. One synthetic
     * value across the documents is what makes a reader who has seen it once recognise it again
     * rather than take it for a card of the demonstration data.
     */
    private static final String PLACEHOLDER_CARD_NUMBER = "4000000000000000";

    /**
     * The card token a published path example carries.
     *
     * <p>Sixty-four zeros, which passes {@link PanMasker#CARD_TOKEN_PATTERN} and is the value
     * {@link PanMasker#ABSENT_CARD_TOKEN} declares for a card that resolved to nothing. A real token
     * is the keyed code over a card number under a deployment-supplied key, so no token this document
     * could publish resolves under a reader's own key, and publishing one derived under the build key
     * would put a value in the document that names a seeded card for anyone holding that key.
     */
    private static final String PLACEHOLDER_CARD_TOKEN = PanMasker.ABSENT_CARD_TOKEN;

    /**
     * The card number of record 1 of {@code app/data/ASCII/carddata.txt}, read to prove the published
     * placeholder token is not that card's token.
     *
     * <p>It is a fixture value of a public repository and names no real card. Nothing sends it: the
     * assertion derives a token from it and requires the published example to differ.
     */
    private static final String SEEDED_CARD_NUMBER = "0500024453765740";

    /** Reads the document and registers its component section. */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readDocument() throws Exception {
        try (InputStream source = OpenApiExampleValidationTest.class
                .getResourceAsStream("/openapi.yaml")) {
            openApi = new Yaml().load(source);
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
     * <p>Twenty-seven examples are validated: the one request example of the update, and the
     * twenty-six the three operations declare beside a status — eight on the list, six on the read
     * and twelve on the update. A response carrying no body contributes none.
     */
    @Test
    @DisplayName("every published example validates against its own schema")
    @SuppressWarnings("unchecked")
    void everyPublishedExampleValidates() {
        int validated = 0;

        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> route = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : route.entrySet()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
                Map<String, Object> declared = (Map<String, Object>) operation.getValue();
                String where = operation.getKey().toUpperCase(Locale.ROOT) + " " + path.getKey();

                validated += validateExamplesOf((Map<String, Object>) declared.get("requestBody"),
                        where + " request");

                Map<String, Object> answers = (Map<String, Object>) declared.get("responses");
                for (Map.Entry<String, Object> answer : answers.entrySet()) {
                    validated += validateExamplesOf((Map<String, Object>) answer.getValue(),
                            where + " " + answer.getKey());
                }
            }
        }

        assertEquals(27, validated,
                "one request example and twenty-six declared beside a response");
    }

    /**
     * Asserts no published example carries a card number of a seeded row.
     *
     * <p>Every one of the fifty rows in {@code app/data/ASCII/carddata.txt} holds a full sixteen-digit
     * card number, and {@code src/main/resources/db/migration/V2__seed.sql} loads all fifty. A
     * published example carrying one of them puts a Primary Account Number in a document a reader
     * copies into a terminal history, an issue tracker and a client fixture.
     *
     * <p>A masked value is a different matter. {@code api/dto/CardSummary} and
     * {@code api/dto/CardDetailResponse} refuse an unmasked value in their own constructors, so the
     * masked form is what this service publishes and what an example of a response has to show.
     */
    @Test
    @DisplayName("no published example carries a card number of a seeded row")
    void noPublishedExampleCarriesASeededCardNumber() {
        for (Map.Entry<String, String> body : everyPublishedBody().entrySet()) {
            Matcher found = SIXTEEN_DIGIT_RUN.matcher(body.getValue());
            while (found.find()) {
                assertEquals(PLACEHOLDER_CARD_NUMBER, found.group(),
                        "every sixteen-digit run a published body carries is the placeholder, never "
                                + "a card number of a seeded row, and " + found.group() + " in "
                                + body.getKey() + " is neither");
            }
        }

        Map<String, Object> variable = pathVariable("cardToken");
        String publishedToken =
                String.valueOf(valueOf(asMap(variable.get("examples")).get("placeholder")));
        assertEquals(PLACEHOLDER_CARD_TOKEN, publishedToken,
                "the path variable publishes the placeholder token");
        assertTrue(publishedToken.matches(CardController.CARD_TOKEN_PATTERN),
                "the placeholder passes the token shape, so the published example is a request this "
                        + "service would read");
        assertNotEquals(PanMasker.cardToken(SEEDED_CARD_NUMBER), publishedToken,
                "the placeholder resolves to no seeded row, so a reader who pastes it reads 404 "
                        + "rather than one card's detail");
    }

    /**
     * Asserts every outcome schema requires all three members and types its null ones.
     *
     * <p>{@code api/dto/CardUpdateResponse} declares three components and the writer serializes all
     * three, so all three arrive on every answer. A schema calling one optional would describe a body
     * a caller could receive without the key, and a caller reading the absence of a key rather than
     * its null value would then treat a rewritten row and a refused one alike.
     */
    @Test
    @DisplayName("every outcome schema requires all three members")
    void everyOutcomeSchemaRequiresAllThreeMembers() {
        Map<String, Map<String, Object>> baselines = Map.of(
                "CardUpdateApplied", updated(),
                "CardUpdateNotFound", notFound(),
                "CardUpdateConflict", conflict(),
                "CardUpdateRejected", rejected());

        baselines.forEach((name, baseline) -> {
            Schema schema = schemaFor(name);
            assertValid(schema, baseline, "the published " + name + " example");

            for (String member : List.of("outcome", "message", "refreshedCard")) {
                Map<String, Object> incomplete = new LinkedHashMap<>(baseline);
                incomplete.remove(member);
                assertInvalid(schema, incomplete, name + " omitting " + member);
            }

            Map<String, Object> extra = new LinkedHashMap<>(baseline);
            extra.put("cardVerificationValue", "747");
            assertInvalid(schema, extra, name + " carrying the card verification value");
        });
    }

    /**
     * Asserts each outcome schema admits only the outcomes and the members its status carries.
     *
     * <p>A rewritten row carries no text and re-reads nothing:
     * {@code app/cbl/COCRDUPC.cbl:L1490} sets the text of a successful rewrite into a field the
     * program leaves unsent. A row another writer changed is the one answer that carries the refreshed
     * row, which {@code app/cbl/COCRDUPC.cbl:L1512-L1517} refreshes once the comparison at L1503-L1508
     * fails. One schema per status is what lets each of those be stated rather than described.
     */
    @Test
    @DisplayName("each outcome schema admits only what its status carries")
    void eachOutcomeSchemaAdmitsOnlyWhatItsStatusCarries() {
        Map<String, Object> rewrittenWithText = updated();
        rewrittenWithText.put("message", "Changes committed to database");
        assertInvalid(schemaFor("CardUpdateApplied"), rewrittenWithText,
                "a rewritten row carrying a text");

        Map<String, Object> rewrittenWithRow = updated();
        rewrittenWithRow.put("refreshedCard", refreshedCard());
        assertInvalid(schemaFor("CardUpdateApplied"), rewrittenWithRow,
                "a rewritten row carrying a refreshed card");

        Map<String, Object> refusedWithRow = rejected();
        refusedWithRow.put("refreshedCard", refreshedCard());
        assertInvalid(schemaFor("CardUpdateRejected"), refusedWithRow,
                "a refused value carrying a refreshed card, which the comparison never read");

        Map<String, Object> wrongOutcome = notFound();
        wrongOutcome.put("outcome", "UPDATED");
        assertInvalid(schemaFor("CardUpdateNotFound"), wrongOutcome,
                "an absent row naming the rewrite outcome");

        Map<String, Object> lockWithoutRow = conflict();
        lockWithoutRow.put("outcome", "LOCK_NOT_ACQUIRED");
        lockWithoutRow.put("message", "Could not lock record for update");
        lockWithoutRow.put("refreshedCard", null);
        assertValid(schemaFor("CardUpdateConflict"), lockWithoutRow,
                "a lock that was not taken reads nothing to refresh");

        Map<String, Object> retryableWrite = updated();
        retryableWrite.put("outcome", "UPDATE_FAILED_AFTER_LOCK");
        for (String name : List.of("CardUpdateApplied", "CardUpdateNotFound", "CardUpdateConflict",
                "CardUpdateRejected")) {
            assertInvalid(schemaFor(name), retryableWrite,
                    name + " naming the outcome that answers the failure shape at 503");
        }
    }

    /**
     * Asserts the update request admits the expiry range the edits admit and refuses every other
     * value.
     *
     * <p>{@code 88 VALID-YEAR VALUES 1950 THRU 2099} at {@code app/cbl/COCRDUPC.cbl:L99} is read by
     * {@code 1260-EDIT-EXPIRY-YEAR} at L934, and {@code 88 VALID-MONTH VALUES 1 THRU 12} at L95 is
     * read by {@code 1250-EDIT-EXPIRY-MON} at L898. A schema admitting four digits promised the
     * caller a year the service refuses.
     */
    @Test
    @DisplayName("the update request encodes the expiry range the edits admit")
    void theUpdateRequestEncodesTheExpiryRangeTheEditsAdmit() {
        Schema request = schemaFor("CardUpdateRequest");
        assertValid(request, updateRequest(), "the published update example");

        for (String year : List.of("1950", "1951", "1999", "2000", "2026", "2099")) {
            Map<String, Object> body = updateRequest();
            body.put("expiryYear", year);
            assertValid(request, body, "an expiry year of " + year);
        }
        for (String refused : List.of("1949", "1900", "2100", "0000", "999", "20266", "20x6")) {
            Map<String, Object> body = updateRequest();
            body.put("expiryYear", refused);
            assertInvalid(request, body, "an expiry year of " + refused);
        }
        for (String refused : List.of("00", "13", "1", "007")) {
            Map<String, Object> body = updateRequest();
            body.put("expiryMonth", refused);
            assertInvalid(request, body, "an expiry month of " + refused);
        }

        Map<String, Object> named = updateRequest();
        named.put("cardNumber", PLACEHOLDER_CARD_NUMBER);
        assertInvalid(request, named,
                "a body naming the card, which the path names and config/RequestJsonStrictnessConfig"
                        + " refuses in the body");
    }

    /**
     * Asserts the page carries its cursor on every answer, null included.
     *
     * <p>{@code api/dto/CardListResponse} serializes the member on every page, and it holds null on
     * the last one. A caller reading the absence of the key rather than its null value would treat a
     * page that omitted it as a last page.
     */
    @Test
    @DisplayName("a page carries its cursor on every answer")
    void aPageCarriesItsCursorOnEveryAnswer() {
        Schema page = schemaFor("CardList");
        assertValid(page, cardPage(), "the published page example");

        Map<String, Object> withCursor = cardPage();
        withCursor.put("nextPageExists", true);
        withCursor.put("nextCursor", "a".repeat(64));
        assertValid(page, withCursor, "a page carrying a cursor");

        Map<String, Object> omitted = cardPage();
        omitted.remove("nextCursor");
        assertInvalid(page, omitted, "a page omitting the cursor member");

        Map<String, Object> shortCursor = cardPage();
        shortCursor.put("nextCursor", "a".repeat(63));
        assertInvalid(page, shortCursor, "a page carrying a cursor of the wrong width");
    }

    /**
     * Asserts the account filter refuses eleven zero digits.
     *
     * <p>{@code CC-ACCT-ID-N EQUAL ZEROS} at {@code app/cbl/COCRDSLC.cbl:L653} is the third absence
     * condition of the source edit, so eleven zeros name no account and
     * {@code app/cbl/COCRDLIC.cbl:L1385-L1394} then applies no filter and walks every account. This
     * route requires the account, so the value is refused rather than accepted and dropped.
     */
    @Test
    @DisplayName("the account filter refuses eleven zero digits")
    @SuppressWarnings("unchecked")
    void theAccountFilterRefusesElevenZeroDigits() {
        Map<String, Object> declared = null;
        for (Object parameter : (List<Object>) node((Map<String, Object>) openApi.get("paths"),
                "/cards", "get", "parameters")) {
            if ("accountId".equals(asMap(parameter).get("name"))) {
                declared = asMap(parameter);
            }
        }
        assertTrue(declared != null, "the list route declares the account filter");

        Schema filter = registry.getSchema(JSON.writeValueAsString(declared.get("schema")),
                InputFormat.JSON);

        assertValid(filter, "00000000050", "the account of fixture row one");
        assertInvalid(filter, "00000000000", "eleven zero digits, which name no account");
        assertInvalid(filter, "0000000005", "ten digits");
        assertInvalid(filter, "0000000005x", "a character that is no digit");
    }

    /** @return the published update request example */
    private static Map<String, Object> updateRequest() {
        return new LinkedHashMap<>(Map.of(
                "embossedName", "Ward Jones",
                "expiryYear", "2026",
                "expiryMonth", "07",
                "expiryDay", "13",
                "activeStatus", "Y"));
    }

    /** @return the published page example */
    private static Map<String, Object> cardPage() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("cardNumber", "************9999");
        row.put("accountId", "00000000050");
        row.put("activeStatus", "Y");

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("cards", List.of(row));
        page.put("nextPageExists", false);
        page.put("nextCursor", null);
        return page;
    }

    /** @return the refreshed snapshot a lost race carries */
    private static Map<String, Object> refreshedCard() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("embossedName", "Ward Jones");
        snapshot.put("expiryYear", "2025");
        snapshot.put("expiryMonth", "07");
        snapshot.put("expiryDay", "13");
        snapshot.put("activeStatus", "Y");
        return snapshot;
    }

    /** @return the body a rewritten row carries */
    private static Map<String, Object> updated() {
        return outcome("UPDATED", null, null);
    }

    /** @return the body an absent row carries */
    private static Map<String, Object> notFound() {
        return outcome("CARD_NOT_FOUND", "Did not find cards for this search condition", null);
    }

    /** @return the body a lost race carries */
    private static Map<String, Object> conflict() {
        return outcome("CHANGED_BEFORE_UPDATE", "Record changed by some one else. Please review",
                refreshedCard());
    }

    /** @return the body a refused value carries */
    private static Map<String, Object> rejected() {
        return outcome("VALIDATION_REJECTED", "Card Active Status must be Y or N", null);
    }

    /**
     * Builds one outcome body.
     *
     * @param name     the outcome
     * @param message  the text it carries, or {@code null}
     * @param snapshot the refreshed row it carries, or {@code null}
     * @return the body
     */
    private static Map<String, Object> outcome(String name, String message,
            Map<String, Object> snapshot) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", name);
        body.put("message", message);
        body.put("refreshedCard", snapshot);
        return body;
    }

    /**
     * Renders every example the document publishes as the text a reader copies.
     *
     * <p>A description is prose and cites a source line where citing one is the point, so only the
     * example values are scanned.
     *
     * @return one rendered body per example, keyed by where the example sits
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> everyPublishedBody() {
        Map<String, String> bodies = new LinkedHashMap<>();

        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> path : paths.entrySet()) {
            Map<String, Object> route = (Map<String, Object>) path.getValue();
            for (Map.Entry<String, Object> operation : route.entrySet()) {
                if (!HTTP_METHODS.contains(operation.getKey())) {
                    continue;
                }
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
        return bodies;
    }

    /**
     * Renders the examples of one request body or response node into the collector.
     *
     * @param into  the collector
     * @param node  the request body or response node, which may be {@code null}
     * @param where what the node is
     */
    @SuppressWarnings("unchecked")
    private static void collectBodies(Map<String, String> into, Map<String, Object> node,
            String where) {

        if (node == null) {
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
     * Validates the examples of one request body or response node.
     *
     * @param node  the node, which may be {@code null} or carry no body
     * @param where what the node is
     * @return the count of examples validated
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
                if (!body.containsKey("example")) {
                    continue;
                }
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
     * Compiles the schema one response or request declares, following a reference.
     *
     * @param declared the schema node of the document
     * @return the compiled schema
     */
    private static Schema schemaOf(Map<String, Object> declared) {
        String reference = String.valueOf(declared.get("$ref"));
        return schemaFor(reference.substring(reference.lastIndexOf('/') + 1));
    }

    /**
     * Reads one path-level variable declaration of the card-numbered route.
     *
     * @param name the variable name
     * @return the declaration
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> pathVariable(String name) {
        for (Object parameter : (List<Object>) node((Map<String, Object>) openApi.get("paths"),
                "/cards/{cardToken}", "parameters")) {
            Map<String, Object> declared = asMap(parameter);
            if (name.equals(declared.get("name"))) {
                return declared;
            }
        }
        throw new AssertionError("the document declares no path variable " + name);
    }

    /**
     * Reads the value of one example node.
     *
     * @param example the example node
     * @return the body it carries
     */
    private static Object valueOf(Object example) {
        return asMap(example).get("value");
    }

    /**
     * Walks a chain of keys.
     *
     * @param from the node to start at
     * @param keys the keys to follow
     * @return the node at the end of the chain
     */
    @SuppressWarnings("unchecked")
    private static Object node(Map<String, Object> from, String... keys) {
        Object current = from;
        for (String key : keys) {
            current = ((Map<String, Object>) current).get(key);
            assertTrue(current != null, "the document declares " + key);
        }
        return current;
    }

    /**
     * Reads one node as a map.
     *
     * @param node the node
     * @return the node as a map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }
}
