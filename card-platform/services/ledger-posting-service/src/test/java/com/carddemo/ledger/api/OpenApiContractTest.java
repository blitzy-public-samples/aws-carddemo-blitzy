package com.carddemo.ledger.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.yaml.snakeyaml.Yaml;

/**
 * Asserts the checked-in interface description matches the one route this service serves.
 *
 * <p>{@code src/main/resources/openapi.yaml} is hand-written, so nothing keeps it true except a test
 * that reads both it and the code. This service was the last of the six to gain one, and it gained it
 * with {@link LedgerApiExceptionHandler}: before that handler existed the document published three
 * statuses and the service answered five, and the two it did not publish were the two an operator
 * needs, because a datastore that was merely away answered the same {@code 500} a defect answers.
 *
 * <p>Every assertion below reads a value out of the document and compares it against the code that
 * has to honour it. Nothing here restates the document to itself.
 */
@DisplayName("The checked-in interface description of the balance query")
final class OpenApiContractTest {

    /** The one path this service serves. */
    private static final String BALANCE_PATH = "/balances/{accountId}";

    /** The three monetary properties the success schema publishes, each as a decimal string. */
    private static final List<String> AMOUNT_PROPERTIES =
            List.of("currentBalance", "cycleCredit", "cycleDebit");

    /** The form every published amount takes: an optional sign, then two fractional digits. */
    private static final String TWO_PLACE_DECIMAL = "^-?\\d{1,10}\\.\\d{2}$";

    /** Java component types paired with the JSON type a property describing one must declare. */
    private static final Map<Class<?>, String> JSON_TYPES =
            Map.of(String.class, "string", int.class, "integer");

    /** Each published schema paired with the record whose components it describes. */
    private static final Map<String, Class<?>> PUBLISHED_RECORDS = Map.of(
            "ApiProblem", ApiProblem.class,
            "AccountBalance", BalanceQueryController.AccountBalance.class);

    /** The document, read once. */
    private static Map<String, Object> document;

    /** Reads the packaged description. */
    @BeforeAll
    static void readDocument() {
        InputStream source = OpenApiContractTest.class.getClassLoader()
                .getResourceAsStream("openapi.yaml");
        assertNotNull(source, "openapi.yaml is packaged");
        document = asMap(new Yaml().load(source));
    }

    /** Asserts the document declares the one operation this service delivers. */
    @Test
    @DisplayName("the document declares the one delivered operation")
    void theDocumentDeclaresTheOneDeliveredOperation() {
        assertEquals("3.1.0", document.get("openapi"), "the version this platform writes");
        assertEquals(Set.of(BALANCE_PATH), paths().keySet(), "one path and no other");
        assertEquals(Set.of("getAccountBalance"), operationIds(), "one operation and no other");
        assertEquals(Set.of("get"), asMap(paths().get(BALANCE_PATH)).keySet(),
                "the route is read-only, and a method the document publishes is a method a caller "
                        + "will try");
    }

    /**
     * Asserts the documented path is the path the controller maps.
     *
     * <p>The operation identifier is a label and the mapping is the behaviour, so the assertion that
     * matters is that the two describe the same route. {@code @RequestMapping} on the class carries
     * the prefix and {@code @GetMapping} on the handler carries the rest.
     */
    @Test
    @DisplayName("the documented path is the path the controller maps")
    void theDocumentedPathIsThePathTheControllerMaps() {
        String prefix = BalanceQueryController.class.getAnnotation(RequestMapping.class).value()[0];

        Method handler = java.util.Arrays.stream(
                        BalanceQueryController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .reduce((first, second) -> {
                    throw new AssertionError("the controller maps more than one GET, so the "
                            + "document has to publish more than one");
                })
                .orElseThrow(() -> new AssertionError("the controller maps no GET at all"));

        assertEquals(BALANCE_PATH, prefix + handler.getAnnotation(GetMapping.class).path()[0],
                "the mapped route and the documented route are the same route");
        assertEquals("balanceOfAccount", handler.getName(),
                "the handler the mapping reaches");
        assertEquals(List.of(MediaType.APPLICATION_JSON_VALUE),
                List.of(handler.getAnnotation(GetMapping.class).produces()),
                "the handler produces the media type the 200 response publishes");
    }

    /**
     * Asserts the path parameter carries the shape the controller constrains it to.
     *
     * <p>The pattern is the whole reason a {@code 400} exists on this route, so the document and the
     * annotation have to carry the same one. A document declaring a looser pattern would promise a
     * caller that a value the route refuses is acceptable.
     */
    @Test
    @DisplayName("the path parameter carries the pattern the controller constrains")
    void thePathParameterCarriesTheControllerPattern() throws Exception {
        Map<String, Object> parameter = asList(operations().get(0), "parameters").get(0);
        Map<String, Object> schema = asMap(parameter.get("schema"));

        assertEquals("accountId", parameter.get("name"), "the parameter the path names");
        assertEquals("path", parameter.get("in"), "it arrives in the path");
        assertEquals(Boolean.TRUE, parameter.get("required"), "a path parameter is required");
        assertEquals("string", schema.get("type"),
                "the identifier travels as text, so a leading zero survives");

        String documented = String.valueOf(schema.get("pattern"));
        assertEquals("^[0-9]{11}$", documented, "eleven digits, from ACCT-ID PIC 9(11)");
        assertEquals(controllerPattern(), documented,
                "the documented pattern is the one the handler constrains its path variable with,"
                        + " so a change in the controller cannot leave this document behind");
        assertTrue("00000000001".matches(documented), "a seeded identifier passes");
        assertFalse("1".matches(documented), "a short value does not");
        assertFalse("abcdefghijk".matches(documented), "eleven letters do not");
    }

    /**
     * Reads the pattern the controller constrains its path variable with.
     *
     * <p>The constant is private, which keeps it off the surface of the service. Reading it
     * reflectively holds the document to the code without widening that surface for a test, and a
     * removal or rename fails here with a message that says which constant went missing rather than
     * with a compile error in a place a reader would not look for one.
     *
     * @return the pattern the handler applies
     */
    private static String controllerPattern() {
        try {
            java.lang.reflect.Field declared =
                    BalanceQueryController.class.getDeclaredField("ACCOUNT_ID_PATTERN");
            declared.setAccessible(true);
            return String.valueOf(declared.get(null));
        } catch (ReflectiveOperationException unreadable) {
            throw new AssertionError("api/BalanceQueryController no longer declares"
                    + " ACCOUNT_ID_PATTERN, so this document cannot be held to it", unreadable);
        }
    }

    /**
     * Asserts the published statuses are the statuses the code answers.
     *
     * <p>This is the assertion the ledger did not have. The document published {@code 200},
     * {@code 400} and {@code 404} while the service also answered {@code 401}, {@code 403} and, for a
     * paused datastore, {@code 500} where {@code 503} is what a caller can act on.
     *
     * <p>{@code 405} and {@code 406} are the two protocol refusals
     * {@link LedgerApiExceptionHandler#onUnsupportedRequest} carries the framework status of. A wrong
     * method answered {@code 500} before that arm existed, so publishing the status is what tells a
     * caller the two answers are different things.
     */
    @Test
    @DisplayName("every published status is one the code answers, and every one it answers is published")
    void thePublishedStatusesAreTheStatusesTheCodeAnswers() {
        assertEquals(
                Set.of("200", "400", "401", "403", "404", "405", "406", "429", "500", "503"),
                responseKeys(BALANCE_PATH),
                "the ten statuses this route answers");

        Set<Integer> handlerStatuses = Set.of(400, 405, 406, 503, 500);
        for (int status : handlerStatuses) {
            assertTrue(responseKeys(BALANCE_PATH).contains(String.valueOf(status)),
                    "api/LedgerApiExceptionHandler answers " + status
                            + ", so the document publishes it");
        }
        assertEquals(handlerStatuses.size() - 1, declaredHandlerArms(),
                "the handler declares one arm per documented failure status, and the protocol arm "
                        + "carries whichever status the framework named, so four arms answer five "
                        + "statuses");
    }

    /**
     * Asserts each failing status carries the problem document under the media type RFC 9457 names.
     *
     * <p>{@code 404} is the one exception and carries no body at all, which the document says.
     */
    @Test
    @DisplayName("every failing status carries the problem document, and 404 carries no body")
    void everyFailingStatusCarriesTheProblemDocument() {
        Map<String, Object> responses = asMap(operations().get(0).get("responses"));

        for (String status : List.of("400", "401", "403", "429", "500", "503")) {
            Map<String, Object> content = asMap(asMap(responses.get(status)).get("content"));
            assertEquals(Set.of("application/problem+json"), content.keySet(),
                    status + " answers one media type, the one RFC 9457 names");
            assertEquals("#/components/schemas/ApiProblem",
                    asMap(asMap(content.get("application/problem+json")).get("schema")).get("$ref"),
                    status + " answers the one error shape this service writes");
        }

        assertFalse(asMap(responses.get("404")).containsKey("content"),
                "404 carries no body, so no shape is published for it");
        assertEquals(Set.of("application/json"),
                asMap(asMap(responses.get("200")).get("content")).keySet(),
                "the success answer is ordinary JSON, not a problem document");
    }

    /**
     * Asserts the published error shape matches {@link ApiProblem}.
     *
     * <p>{@code additionalProperties: false} is what makes the promise testable: a body carrying a
     * fifth member would fail validation against the document, and the framework body this handler
     * replaced carried two members this one does not.
     */
    @Test
    @DisplayName("the error schema matches the record, and admits no fifth member")
    void theErrorSchemaMatchesTheRecord() {
        Map<String, Object> schema = schema("ApiProblem");
        List<String> recordComponents = java.util.Arrays.stream(
                        ApiProblem.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();

        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "no fifth member");
        assertEquals(recordComponents, schema.get("required"),
                "every member of the record is required, in the order the record declares them");
        assertEquals(new LinkedHashSet<>(recordComponents), asMap(schema.get("properties")).keySet(),
                "the properties are the components of api/ApiProblem and no others");

        Map<String, Object> properties = asMap(schema.get("properties"));
        assertEquals(List.of(ApiProblem.ABOUT_BLANK), asMap(properties.get("type")).get("enum"),
                "the one problem type the record writes");
        assertTrue(String.valueOf(asMap(properties.get("title")).get("enum"))
                        .contains(ApiProblem.SERVICE_UNAVAILABLE),
                "the title enumeration carries the unavailability title: "
                        + asMap(properties.get("title")).get("enum"));
        assertTrue(String.valueOf(asMap(properties.get("status")).get("enum")).contains("503"),
                "the status enumeration carries 503: "
                        + asMap(properties.get("status")).get("enum"));
    }

    /**
     * Asserts the published success shape matches the record the handler returns, and that every
     * amount is published as a decimal string rather than as a number.
     *
     * <p>The error schema above was already held to its record. This one was not, so a property the
     * record does not carry, a renamed one, or a missing one could be published and read as correct.
     *
     * <p>The string form is the point rather than a preference. {@code ACCT-CURR-BAL PIC S9(10)V99}
     * at {@code app/cpy/CVACT01Y.cpy:L7} carries ten integer digits and two fractional digits, which
     * is more precision than a double holds exactly. Most parsers read a JSON number into a double,
     * so publishing these as numbers would put binary floating point back into the values this
     * platform's equivalence argument rests on being fixed point.
     */
    @Test
    @DisplayName("the balance schema matches the record, and every amount is a decimal string")
    void theBalanceSchemaMatchesTheRecord() {
        Map<String, Object> schema = schema("AccountBalance");
        List<java.lang.reflect.RecordComponent> components =
                List.of(BalanceQueryController.AccountBalance.class.getRecordComponents());
        List<String> names = components.stream()
                .map(java.lang.reflect.RecordComponent::getName).toList();
        Map<String, Object> properties = asMap(schema.get("properties"));

        assertEquals(Boolean.FALSE, schema.get("additionalProperties"), "no fifth member");
        assertEquals(names, schema.get("required"),
                "every member of the record is required, in the order the record declares them");
        assertEquals(new LinkedHashSet<>(names), properties.keySet(),
                "the properties are the components of the returned record and no others");
        assertEquals(List.of(), components.stream()
                        .filter(component -> component.getType() != String.class).toList(),
                "every component is declared as text, so no amount can be widened to a number");
        assertEquals(controllerPattern(), asMap(properties.get("accountId")).get("pattern"),
                "the echoed identifier carries the pattern the handler constrains");

        for (String amount : AMOUNT_PROPERTIES) {
            assertTrue(properties.containsKey(amount),
                    () -> "the schema publishes " + amount + ", found " + properties.keySet());
            Map<String, Object> published = asMap(properties.get(amount));
            assertEquals("string", published.get("type"),
                    amount + " is published as a decimal string, never as a number");
            assertEquals(TWO_PLACE_DECIMAL, published.get("pattern"),
                    amount + " carries the two fractional digits PIC S9(10)V99 declares");
        }
    }

    /**
     * Asserts every property of both published schemas declares the JSON type its record component
     * holds.
     *
     * <p>The two tests above hold each schema to the <em>names</em> its record declares. Neither held
     * it to the types, so a property could describe a number where the record carries text, or the
     * reverse. A generated client reads the declared type rather than the description, so that drift
     * reaches callers as a deserialization failure or, worse, as a silently rounded amount.
     *
     * <p>The claim is made over every property of both schemas rather than over the one that drifted,
     * so a property added later is covered without this test being revisited.
     */
    @Test
    @DisplayName("every published property declares the JSON type its record component holds")
    void everyPublishedPropertyDeclaresItsComponentType() {
        List<String> mismatches = new java.util.ArrayList<>();
        for (Map.Entry<String, Class<?>> published : PUBLISHED_RECORDS.entrySet()) {
            Map<String, Object> properties = asMap(schema(published.getKey()).get("properties"));
            for (java.lang.reflect.RecordComponent component
                    : published.getValue().getRecordComponents()) {
                String expected = JSON_TYPES.get(component.getType());
                assertNotNull(expected, () -> "this test carries no JSON type for "
                        + component.getType() + ", newly held by " + published.getKey() + "."
                        + component.getName());
                if (!properties.containsKey(component.getName())) {
                    mismatches.add(published.getKey() + " publishes no " + component.getName());
                    continue;
                }
                Object declared = asMap(properties.get(component.getName())).get("type");
                if (!expected.equals(declared)) {
                    mismatches.add(published.getKey() + "." + component.getName() + " declares "
                            + declared + " for a " + component.getType().getSimpleName());
                }
            }
        }

        assertEquals(List.of(), mismatches,
                "properties whose declared type is not the type their component holds");
    }

    /**
     * Asserts every detail the record declares is published as an example.
     *
     * <p>A detail nobody published is a text a caller cannot match on, and the three are separate
     * precisely so a caller can tell a refusal from an outage from a fault.
     */
    @Test
    @DisplayName("each documented example detail is a constant of the record")
    void eachDocumentedDetailIsAConstantOfTheRecord() {
        String rendered = new Yaml().dump(document);

        for (String detail : List.of(ApiProblem.INVALID_ACCOUNT_ID,
                ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE, ApiProblem.BALANCE_NOT_READ)) {
            assertTrue(rendered.contains(detail),
                    "the document publishes the text the handler answers: " + detail);
        }
    }

    /** Asserts the one operation inherits the one authentication rule rather than restating it. */
    @Test
    @DisplayName("the operation inherits the one basic-authentication rule")
    void theOperationInheritsTheOneAuthenticationRule() {
        assertEquals(List.of(Map.of("basicIdentity", List.of())), document.get("security"),
                "one document-level rule");
        assertEquals(Set.of("basicIdentity"), securitySchemes().keySet(), "one scheme");
        assertEquals("http", asMap(securitySchemes().get("basicIdentity")).get("type"),
                "the scheme is HTTP authentication");
        assertEquals("basic", asMap(securitySchemes().get("basicIdentity")).get("scheme"),
                "and the basic scheme, which config/SecurityConfig installs");
        assertFalse(operations().get(0).containsKey("security"),
                "the operation inherits the rule and restates nothing");
    }

    /**
     * Asserts the document names no value a caller would be wrong to send.
     *
     * <p>This service holds no card number and no verification value, and its error body echoes no
     * request value. A document naming either would be describing a body this service does not write.
     */
    @Test
    @DisplayName("no part of the document names a card number, a verification value or a request echo")
    void noPartOfTheDocumentNamesACardNumberOrARequestEcho() {
        String text = new Yaml().dump(document).toLowerCase(java.util.Locale.ROOT);

        assertFalse(text.contains("cardnumber"), "this service holds no card number");
        assertFalse(text.contains("maskedcard"), "nor a masked one");
        assertFalse(text.contains("verification"), "nor a verification value");
        assertFalse(text.contains("cvv"), "nor a verification value under its short name");
        assertFalse(asMap(schema("ApiProblem").get("properties")).containsKey("path"),
                "the error body echoes no resolved path, unlike the framework body it replaced");
        assertFalse(asMap(schema("ApiProblem").get("properties")).containsKey("timestamp"),
                "nor a timestamp");
    }

    /** Counts the exception-handling arms {@link LedgerApiExceptionHandler} declares. */
    private static long declaredHandlerArms() {
        return java.util.Arrays.stream(LedgerApiExceptionHandler.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(ExceptionHandler.class))
                .count();
    }

    /** Returns every operation identifier the document declares. */
    private static Set<String> operationIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> operation : operations()) {
            ids.add(String.valueOf(operation.get("operationId")));
        }
        return ids;
    }

    /** Returns every operation the document declares. */
    private static List<Map<String, Object>> operations() {
        return paths().values().stream()
                .map(OpenApiContractTest::asMap)
                .flatMap(path -> path.entrySet().stream())
                .filter(entry -> Set.of("get", "post", "put", "patch", "delete")
                        .contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .map(OpenApiContractTest::asMap)
                .toList();
    }

    /** Returns the status codes one path publishes. */
    private static Set<String> responseKeys(String path) {
        return asMap(asMap(asMap(paths().get(path)).get("get")).get("responses")).keySet();
    }

    /** Returns the paths block. */
    private static Map<String, Object> paths() {
        return asMap(document.get("paths"));
    }

    /** Returns the security schemes block. */
    private static Map<String, Object> securitySchemes() {
        return asMap(asMap(document.get("components")).get("securitySchemes"));
    }

    /**
     * Returns one named schema.
     *
     * @param name the schema name
     * @return the schema
     */
    private static Map<String, Object> schema(String name) {
        return asMap(asMap(asMap(document.get("components")).get("schemas")).get(name));
    }

    /**
     * Reads one value as a map.
     *
     * @param value the parsed value
     * @return the same value as a map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * Reads one member as a list of maps.
     *
     * @param owner the owning map
     * @param key   the member to read
     * @return the member as a list of maps
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Map<String, Object> owner, String key) {
        return (List<Map<String, Object>>) owner.get(key);
    }
}
