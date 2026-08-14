package com.carddemo.fraud.api;

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
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

final class OpenApiContractTest {

    private static Map<String, Object> document;

    @BeforeAll
    static void readDocument() {
        InputStream source = OpenApiContractTest.class.getClassLoader()
                .getResourceAsStream("openapi.yaml");
        assertNotNull(source, "openapi.yaml is packaged");
        document = asMap(new Yaml().load(source));
    }

    @Test
    void theDocumentDeclaresTheDeliveredTwoOperations() {
        assertEquals("3.1.0", document.get("openapi"));
        assertEquals(Set.of("/fraud-assessments", "/fraud-assessments/{transactionId}"),
                paths().keySet());
        assertEquals(Set.of("assessmentsOfAccount", "assessmentOfTransaction"),
                operationIds());
    }

    @Test
    void everyOperationIdNamesAControllerMethod() {
        Set<String> controllerMethods = new LinkedHashSet<>();
        for (Method method : FraudAssessmentController.class.getDeclaredMethods()) {
            controllerMethods.add(method.getName());
        }
        assertTrue(controllerMethods.containsAll(operationIds()));
    }

    @Test
    void bothOperationsInheritBasicAuthentication() {
        assertEquals(List.of(Map.of("basicIdentity", List.of())), document.get("security"));
        assertEquals(Set.of("basicIdentity"), securitySchemes().keySet());
        assertEquals("http", asMap(securitySchemes().get("basicIdentity")).get("type"));
        assertEquals("basic", asMap(securitySchemes().get("basicIdentity")).get("scheme"));

        for (Map<String, Object> operation : operations()) {
            assertFalse(operation.containsKey("security"),
                    "operations inherit the one top-level security rule");
        }
    }

    @Test
    void theCollectionParametersMatchTheControllerContract() {
        List<Map<String, Object>> parameters = collectionParameters();
        assertEquals(List.of("accountId", "X-Fraud-Cursor", "size", "sort", "page"),
                parameters.stream().map(parameter -> parameter.get("name")).toList(),
                "the document names every parameter the one handler reads, the two refused ones "
                        + "included");
        assertEquals(Boolean.TRUE, parameters.get(0).get("required"));
        assertEquals("^[0-9]{11}$",
                asMap(parameters.get(0).get("schema")).get("pattern"));

        for (int at = 1; at < parameters.size(); at++) {
            Map<String, Object> parameter = parameters.get(at);
            assertEquals(Boolean.FALSE, parameter.get("required"),
                    parameter.get("name") + " is optional");
            assertEquals("string", asMap(parameter.get("schema")).get("type"),
                    parameter.get("name") + " travels as text, so a present-empty value is a value "
                            + "the caller sent and not an omitted parameter");
            assertFalse(asMap(parameter.get("schema")).containsKey("default"),
                    parameter.get("name") + " declares no schema default, which would describe a "
                            + "present-empty value as taking one");
        }
        assertPublishedShapeMatchesTheHandler(parameterNamed(FraudAssessmentController.SIZE_PARAMETER), SIZE_CANDIDATES,
                FraudAssessmentController.MINIMUM_PAGE_SIZE,
                FraudAssessmentController.MAXIMUM_PAGE_SIZE);
    }

    /**
     * Size values this test drives the published shape and the handler's bounds through together.
     *
     * <p>The set brackets both bounds, so a pattern that admitted zero rows or a page above
     * {@link FraudAssessmentController#MAXIMUM_PAGE_SIZE} fails here.
     */
    private static final List<String> SIZE_CANDIDATES = List.of(
            "0", "1", "001", "20", "199", "200", "0200", "201", "999", "1000");

    /**
     * Holds one published parameter shape against the bounds the handler actually enforces.
     *
     * <p>This is the assertion whose absence let the document drift: the published shapes admitted
     * seven-digit pages and three-digit sizes while
     * {@code FraudAssessmentController.boundedNumberOf} refused everything above 1000000 and above
     * 200, so a generated client accepted values the route answers 400 to. Pinning the pattern text
     * would not have caught it, because the pattern text was exactly what was wrong. This compares the
     * pattern against the constants the handler reads, one value at a time, and reports the first
     * value the two disagree on.
     *
     * @param parameter  the published parameter, carrying its schema
     * @param candidates digit forms to test, bracketing the bounds
     * @param lowest     the lowest value the handler accepts, read from the controller
     * @param highest    the highest value the handler accepts, read from the controller
     */
    private static void assertPublishedShapeMatchesTheHandler(Map<String, Object> parameter,
            List<String> candidates, int lowest, int highest) {

        String pattern = String.valueOf(asMap(parameter.get("schema")).get("pattern"));
        for (String candidate : candidates) {
            boolean published = candidate.matches(pattern);
            boolean accepted = handlerAccepts(candidate, lowest, highest);
            assertEquals(accepted, published,
                    () -> "the published " + parameter.get("name") + " shape " + pattern
                            + " and the handler bounds " + lowest + " through " + highest
                            + " disagree about the value " + candidate + ": the document "
                            + (published ? "admits" : "refuses") + " it and the route "
                            + (accepted ? "accepts" : "answers 400 to") + " it");
        }
    }

    /**
     * Reports whether the handler accepts one paging value, by the rule
     * {@code FraudAssessmentController.boundedNumberOf} applies.
     *
     * @param value   the value as it would arrive on the query string
     * @param lowest  the lowest value accepted
     * @param highest the highest value accepted
     * @return {@code true} where the route reads it as a page rather than answering 400
     */
    private static boolean handlerAccepts(String value, int lowest, int highest) {
        int number;
        try {
            number = Integer.parseInt(value.strip());
        } catch (NumberFormatException notANumber) {
            return false;
        }
        return number >= lowest && number <= highest;
    }

    /**
     * The page is continued by a header the caller returns, not by a number it composes.
     *
     * <p>The location matters as much as the name. A cursor declared in the query string would be
     * recorded by every access log that records one, and it carries a transaction identifier.
     */
    @Test
    void theCursorIsAHeaderAndCarriesAnExampleOfItsShape() {
        Map<String, Object> cursor = parameterNamed("X-Fraud-Cursor");
        Map<String, Object> schema = asMap(cursor.get("schema"));
        assertEquals("header", cursor.get("in"), "the cursor travels as a header");
        assertFalse(schema.containsKey("pattern"),
                "the cursor carries no published shape to compose against: a caller returns the "
                        + "value it was given, and the handler refuses any other");
        assertEquals(List.of("2022-06-10T19:27:53.418Z|0000000380632461"), schema.get("examples"),
                "the example shows both ordering values and the separator joining them");
    }

    /**
     * A page number is published as refused, and published without a numeric shape.
     *
     * <p>A pattern admitting digits would describe a value this route accepts. It accepts none: an
     * offset is reached by reading and discarding every row before it.
     */
    @Test
    void thePageParameterIsPublishedAsRefusedAndNamesNoNumericShape() {
        Map<String, Object> page = parameterNamed("page");
        assertFalse(asMap(page.get("schema")).containsKey("pattern"),
                "no numeric shape is published for a parameter every value of which answers 400");
        assertTrue(String.valueOf(page.get("description")).contains("does not accept"),
                "the description states the refusal rather than describing a page it would serve");
    }

    /**
     * The page envelope matches the record the handler returns.
     *
     * <p>{@code nextCursor} is optional because the last page omits it rather than rendering it
     * null, which is the shape {@code AssessmentPage} produces through {@code JsonInclude}.
     */
    @Test
    void thePageSchemaMatchesTheEnvelopeRecord() {
        Map<String, Object> schema = schema("AssessmentPage");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(List.of("assessments", "nextPageExists"), schema.get("required"),
                "nextCursor is absent from a last page, so it is not required");
        assertEquals(Set.of("assessments", "nextPageExists", "nextCursor"),
                asMap(schema.get("properties")).keySet());

        Map<String, Object> rows = asMap(asMap(schema.get("properties")).get("assessments"));
        assertEquals("array", rows.get("type"));
        assertEquals(FraudAssessmentController.MAXIMUM_PAGE_SIZE, rows.get("maxItems"),
                "the published ceiling is the one the handler enforces");
        assertEquals("#/components/schemas/FraudAssessment", asMap(rows.get("items")).get("$ref"));
    }

    private static List<Map<String, Object>> collectionParameters() {
        return asList(asMap(asMap(paths().get("/fraud-assessments")).get("get")), "parameters");
    }

    private static Map<String, Object> parameterNamed(String name) {
        return collectionParameters().stream()
                .filter(parameter -> name.equals(parameter.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no parameter named " + name));
    }

    @Test
    void everyReadMappingOfTheControllerIsDescribedByAnOperation() {
        long conditionalMappings = java.util.Arrays.stream(
                        FraudAssessmentController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(
                        org.springframework.web.bind.annotation.GetMapping.class))
                .filter(java.util.Objects::nonNull)
                .filter(mapping -> mapping.params().length > 0)
                .count();

        assertEquals(0, conditionalMappings,
                "a mapping selected by a query parameter answers requests no operation of this "
                        + "document describes");
        assertEquals(2, operationIds().size(), "one operation per read mapping");
    }

    @Test
    void theTransactionParameterKeepsItsSixteenCharacterWidth() {
        List<Map<String, Object>> parameters = asList(
                asMap(asMap(paths().get("/fraud-assessments/{transactionId}")).get("get")),
                "parameters");
        Map<String, Object> schema = asMap(parameters.get(0).get("schema"));
        assertEquals("transactionId", parameters.get(0).get("name"));
        assertEquals("path", parameters.get(0).get("in"));
        assertEquals(Boolean.TRUE, parameters.get(0).get("required"));
        assertEquals(16, schema.get("minLength"));
        assertEquals(16, schema.get("maxLength"));
    }

    @Test
    void responseStatusSetsMatchTheDeliveredSurface() {
        assertEquals(Set.of("200", "400", "401", "403", "405", "406", "429", "500"),
                responseKeys("/fraud-assessments"));
        assertEquals(Set.of("200", "400", "401", "403", "404", "405", "406", "429", "500"),
                responseKeys("/fraud-assessments/{transactionId}"));
    }

    @Test
    void theAssessmentSchemaMatchesTheResponseRecord() {
        Map<String, Object> schema = schema("FraudAssessment");
        assertEquals(Boolean.FALSE, schema.get("additionalProperties"));
        assertEquals(List.of("transactionId", "accountId", "riskScore", "triggeredRules",
                "flagged", "assessedAt"), schema.get("required"));
        assertEquals(Set.of("transactionId", "accountId", "riskScore", "triggeredRules",
                "flagged", "assessedAt"), asMap(schema.get("properties")).keySet());
    }

    @Test
    void riskBoundsAndRuleIdentifiersMatchTheEventContract() {
        Map<String, Object> properties = asMap(schema("FraudAssessment").get("properties"));
        Map<String, Object> score = asMap(properties.get("riskScore"));
        Map<String, Object> rules = asMap(properties.get("triggeredRules"));
        assertEquals(0, score.get("minimum"));
        assertEquals(100, score.get("maximum"));
        assertEquals(Boolean.TRUE, rules.get("uniqueItems"));
        assertEquals(List.of("VELOCITY", "AMOUNT_ANOMALY", "MERCHANT_CATEGORY"),
                asMap(rules.get("items")).get("enum"));
    }

    @Test
    void noSchemaNamesACardNumberOrVerificationValue() {
        String text = new Yaml().dump(document).toLowerCase();
        assertFalse(text.contains("cardnumber"));
        assertFalse(text.contains("maskedcard"));
        assertFalse(text.contains("verification"));
        assertFalse(text.contains("cvv"));
    }

    private static Set<String> operationIds() {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> operation : operations()) {
            ids.add(String.valueOf(operation.get("operationId")));
        }
        return ids;
    }

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

    private static Set<String> responseKeys(String path) {
        return asMap(asMap(asMap(paths().get(path)).get("get")).get("responses")).keySet();
    }

    private static Map<String, Object> paths() {
        return asMap(document.get("paths"));
    }

    private static Map<String, Object> securitySchemes() {
        return asMap(asMap(document.get("components")).get("securitySchemes"));
    }

    private static Map<String, Object> schema(String name) {
        return asMap(asMap(asMap(document.get("components")).get("schemas")).get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asList(Map<String, Object> owner, String key) {
        return (List<Map<String, Object>>) owner.get(key);
    }
}