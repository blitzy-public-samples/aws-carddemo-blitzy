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
        List<Map<String, Object>> parameters =
                asList(asMap(asMap(paths().get("/fraud-assessments")).get("get")),
                        "parameters");
        assertEquals(List.of("accountId", "page", "size", "sort"),
                parameters.stream().map(parameter -> parameter.get("name")).toList(),
                "the document names every parameter the one handler reads, sort included");
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
        assertEquals("^[0-9]{1,7}$", asMap(parameters.get(1).get("schema")).get("pattern"),
                "the published page shape admits the numbers the handler reads");
        assertEquals("^[0-9]{1,3}$", asMap(parameters.get(2).get("schema")).get("pattern"),
                "the published size shape admits the numbers the handler reads");
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