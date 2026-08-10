package com.carddemo.fraud.api;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.fraud.repository.FraudAssessmentRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Pins the read surface of the fraud detection service from three sides. Reflection supplies the
 * facts of the compiled controller. A parse of the packaged Representational State Transfer (REST)
 * description supplies the facts of the published contract. A read-only scan of the Java sources
 * supplies the literals and package references reflection cannot reach. No Spring context starts,
 * no database opens and no broker is contacted.
 *
 * <p>Common Business Oriented Language (COBOL) holds no risk-scoring program, so the surface
 * asserted here is net new; no COBOL ancestor exists.
 */
final class FraudApiContractTest {

    /** Classpath name of the packaged description this class reads. */
    private static final String DOCUMENT_RESOURCE = "/openapi.yaml";

    /** Module-relative path of the controller source this class scans. */
    private static final String CONTROLLER_SOURCE =
            "src/main/java/com/carddemo/fraud/api/FraudAssessmentController.java";

    /** Module-relative root of the production sources of this service. */
    private static final String MAIN_SOURCE_ROOT = "src/main/java/com/carddemo/fraud";

    /** Module-relative root of the test sources of this service. */
    private static final String TEST_SOURCE_ROOT = "src/test/java/com/carddemo/fraud";

    /** Suffix identifying a Java source file during a scan. */
    private static final String JAVA_SUFFIX = ".java";

    /** The one base path the controller declares. */
    private static final String BASE_PATH = "/fraud-assessments";

    /**
     * Shape a header parameter's name takes: an {@code X-} extension prefix, then one or more
     * hyphen-joined words each beginning with a capital. This is the convention a header field
     * follows, and it is deliberately not camel case.
     */
    private static final java.util.regex.Pattern HEADER_FIELD_NAME =
            java.util.regex.Pattern.compile("^X(-[A-Z][a-z]+)+$");

    /** The one path template the controller declares below its base path. */
    private static final String ITEM_PATH_TEMPLATE = "/{transactionId}";

    /** Width the transaction identifier constraint fixes, in characters. */
    private static final int TRANSACTION_ID_WIDTH = 16;

    /** Regular expression the account identifier constraint applies. */
    private static final String ACCOUNT_ID_REGEX = "^[0-9]{11}$";

    /** Components the response record declares, in declaration order. */
    private static final List<String> RESPONSE_COMPONENTS = List.of("transactionId", "accountId",
            "riskScore", "triggeredRules", "flagged", "assessedAt");

    /** Types the response record declares, positionally matched to the component names. */
    private static final List<Class<?>> RESPONSE_COMPONENT_TYPES = List.of(String.class,
            String.class, int.class, List.class, boolean.class, Instant.class);

    /**
     * Component types paired with the JSON type a property describing one must declare. An instant
     * is text because it is rendered in the ISO-8601 form rather than as an epoch number.
     */
    private static final Map<Class<?>, String> WIRE_TYPES = Map.of(
            String.class, "string",
            int.class, "integer",
            List.class, "array",
            boolean.class, "boolean",
            Instant.class, "string");

    /** Component whose presence on both sides of the contract this class pins. */
    private static final String VERDICT_COMPONENT = "flagged";

    /** Name of the response schema the description publishes. */
    private static final String ASSESSMENT_SCHEMA = "FraudAssessment";

    /** Name of the error schema the description publishes. */
    private static final String PROBLEM_SCHEMA = "Problem";

    /** Path prefixes no mapping value may carry. */
    private static final List<String> BANNED_MAPPING_PREFIXES = List.of("/api", "/v1");

    /** Actions no mapping value may name. */
    private static final List<String> BANNED_MAPPING_ACTIONS =
            List.of("score", "replay", "reset", "recalculate", "trigger", "simulate");

    /** Digit-fixing expressions the transaction identifier constraint must not apply. */
    private static final List<String> DIGIT_PATTERN_FRAGMENTS =
            List.of("[0-9]{16}", "\\d{16}", "^[0-9]+$");

    /** Root every package name in this platform extends. */
    private static final String PACKAGE_ROOT = "com.carddemo.";

    /**
     * Leaf names of the five sibling service packages this module may not reference. The card leaf
     * carries its separator so the fraud package itself never matches.
     */
    private static final List<String> FOREIGN_SERVICE_LEAVES =
            List.of("authorization", "ledger", "notification", "account", "card.");

    /**
     * Outbound client types this module may not hold, each spelled in halves so a scan of this file
     * never matches its own search text.
     */
    private static final List<String> OUTBOUND_CLIENT_TYPES = List.of("Rest" + "Template",
            "Rest" + "Client", "Web" + "Client", "Http" + "Client", "java.net." + "http");

    /** Marker of a REST controller type, spelled in halves for the same reason. */
    private static final String REST_CONTROLLER_MARKER = "@" + "RestController";

    /** Marker of a plain controller type, spelled in halves for the same reason. */
    private static final String CONTROLLER_MARKER = "@" + "Controller";

    /** File name of the one controller type this service declares. */
    private static final String CONTROLLER_FILE_NAME = "FraudAssessmentController.java";

    /**
     * Marker of an advice type, spelled in halves for the same reason.
     *
     * <p>{@value #REST_CONTROLLER_MARKER} is a prefix of this marker, so a source carrying an advice
     * matches both. The controller count therefore excludes the advice file by name, and this marker
     * counts the advice on its own.
     */
    private static final String ADVICE_MARKER = "@" + "RestControllerAdvice";

    /** File name of the one advice type this service declares. */
    private static final String ADVICE_FILE_NAME = "FraudApiExceptionHandler.java";

    /** Operations no path item may declare. */
    private static final List<String> BANNED_OPERATIONS =
            List.of("post", "put", "patch", "delete", "head", "options", "trace");

    /**
     * Response keys no operation may declare.
     *
     * <p>Each one is a status no code path of this service produces, so publishing it would tell a
     * caller to handle an answer it will never receive. {@code 429} used to sit here and no longer
     * does: {@code config/RequestRateCeilingFilter} answers it, and the description publishes it on
     * both routes.
     */
    private static final List<String> BANNED_RESPONSE_KEYS =
            List.of("default", "409", "422", "502");

    /** Document keys the description must not carry at any depth. */
    private static final List<String> BANNED_DOCUMENT_KEYS =
            List.of("webhooks", "callbacks", "externalDocs");

    /** Prefix marking a specification extension key. */
    private static final String EXTENSION_PREFIX = "x-";

    /** Key naming an internal reference. */
    private static final String REFERENCE_KEY = "$ref";

    /** Prefix every reference in the description carries. */
    private static final String REFERENCE_PREFIX = "#/";

    /** Scheme no reference may carry. */
    private static final String NETWORK_SCHEME = "http";

    /** Operational path fragments the description deliberately omits. */
    private static final List<String> OPERATIONAL_FRAGMENTS =
            List.of("actuator", "health", "metrics", "prometheus");

    /** The parsed description. */
    private static Map<String, Object> document;

    /** The resolved module base directory. */
    private static Path moduleBase;

    /** Lines of the controller source, read once. */
    private static List<String> controllerLines;

    @BeforeAll
    static void readTheShippedContractAndSources() throws IOException {
        try (InputStream packaged = FraudApiContractTest.class
                .getResourceAsStream(DOCUMENT_RESOURCE)) {
            assertNotNull(packaged, DOCUMENT_RESOURCE + " is absent from the test classpath");
            document = asMap(new Yaml(new SafeConstructor(new LoaderOptions())).load(packaged),
                    DOCUMENT_RESOURCE);
        }
        moduleBase = resolveModuleBase();
        controllerLines =
                Files.readAllLines(moduleBase.resolve(CONTROLLER_SOURCE), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("The assessment record declares six components in order")
    void theControllerNestsOneRecordOfSixComponents() {
        Class<?> record = responseRecord();
        List<String> declared = Arrays.stream(record.getRecordComponents())
                .map(RecordComponent::getName).toList();
        assertEquals(RESPONSE_COMPONENTS, declared,
                "the response record declares its components in this order");
    }

    /**
     * The set of records nested in the controller is closed at three, each with a stated job.
     *
     * <p>Naming the set keeps a fourth from appearing unnoticed. {@code AssessmentPage} is the
     * envelope one page answers, {@code FraudAssessment} is one row of it, and {@code Position} is
     * the pair a cursor names, which never leaves the class.
     */
    @Test
    @DisplayName("The controller nests the page envelope, the assessment and the cursor position")
    void theControllerNestsOnlyTheThreeRecordsItsAnswerNeeds() {
        Set<String> nested = Arrays.stream(FraudAssessmentController.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .map(Class::getSimpleName)
                .collect(java.util.stream.Collectors.toSet());

        assertEquals(Set.of("AssessmentPage", "FraudAssessment", "Position"), nested,
                "records nested in the controller");
    }

    /**
     * The page envelope declares the rows and the two values that describe the page after them.
     *
     * <p>No whole-history count appears. Counting an account's rows reads every one of them, which
     * is the work a page exists to avoid, so the envelope answers whether more rows exist rather
     * than how many.
     */
    @Test
    @DisplayName("The page envelope declares the rows, the paging flag and the cursor, and no count")
    void thePageEnvelopeDeclaresItsThreeComponents() {
        List<String> declared = Arrays.stream(
                        nestedRecordNamed("AssessmentPage").getRecordComponents())
                .map(RecordComponent::getName).toList();

        assertEquals(List.of("assessments", "nextPageExists", "nextCursor"), declared,
                "the page envelope declares its components in this order");
    }

    /**
     * The cursor position stays inside the controller.
     *
     * <p>It is bookkeeping for one read, not a shape a caller receives. A public one would become an
     * interface this route has to keep.
     */
    @Test
    @DisplayName("The cursor position is private to the controller")
    void theCursorPositionIsPrivateToTheController() {
        assertTrue(Modifier.isPrivate(nestedRecordNamed("Position").getModifiers()),
                "the cursor position is reachable from outside the controller");
    }

    @Test
    @DisplayName("The record declares two texts, a whole number, a list, a boolean and an instant")
    void theRecordComponentsCarryTheirDeclaredTypes() {
        RecordComponent[] components = responseRecord().getRecordComponents();
        assertEquals(RESPONSE_COMPONENT_TYPES.size(), components.length, "component count");
        List<Class<?>> types =
                Arrays.stream(components).map(RecordComponent::getType).<Class<?>>map(type -> type)
                        .toList();
        assertEquals(RESPONSE_COMPONENT_TYPES, types,
                "the response record declares these component types positionally");
    }

    /**
     * Asserts every published property declares the wire type its component serializes to.
     *
     * <p>The two tests above hold the record to its component names and types, and the description to
     * the same names. Neither held the description to the same <em>types</em>, so a property could
     * describe a number where the record sends text. Publishing {@code accountId} as a number passed
     * every test in this module while losing a leading zero, and every account identifier in
     * {@code app/data/ASCII/acctdata.txt} is eleven digits padded on the left with them.
     *
     * <p>A generated client reads the declared type rather than the description, so this is the
     * assertion that keeps a documented type from diverging from the value actually sent.
     */
    @Test
    @DisplayName("Every published property declares the wire type its component serializes to")
    void everyPublishedPropertyDeclaresItsWireType() {
        Map<String, Object> properties = asMap(asMap(
                nodeAt("components", "schemas", ASSESSMENT_SCHEMA), ASSESSMENT_SCHEMA)
                .get("properties"), ASSESSMENT_SCHEMA + " properties");
        List<String> mismatches = new ArrayList<>();

        for (int position = 0; position < RESPONSE_COMPONENTS.size(); position++) {
            String property = RESPONSE_COMPONENTS.get(position);
            Class<?> componentType = RESPONSE_COMPONENT_TYPES.get(position);
            String expected = WIRE_TYPES.get(componentType);
            assertNotNull(expected, () -> "this test carries no wire type for "
                    + componentType.getName() + ", newly held by " + property
                    + ". Add the mapping deliberately rather than letting it default to a number.");
            if (!properties.containsKey(property)) {
                mismatches.add(ASSESSMENT_SCHEMA + " publishes no " + property);
                continue;
            }
            Object declared = asMap(properties.get(property), property).get("type");
            if (!expected.equals(declared)) {
                mismatches.add(property + " declares " + declared + " for a "
                        + componentType.getSimpleName());
            }
        }

        assertEquals(List.of(), mismatches,
                "properties whose declared type is not the type their component sends");
    }

    /**
     * Asserts the description names the property set the response record declares, taking the
     * expected set from the record by reflection.
     *
     * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
     */
    @Test
    @DisplayName("The description and the response record name one property set, and both carry the "
            + "stored verdict")
    void theDescriptionNamesThePropertySetTheRecordDeclares() {
        Set<String> fromRecord = new LinkedHashSet<>(Arrays.stream(
                responseRecord().getRecordComponents()).map(RecordComponent::getName).toList());
        Set<String> fromDocument =
                new LinkedHashSet<>(asMap(schema(ASSESSMENT_SCHEMA).get("properties"),
                        ASSESSMENT_SCHEMA + " properties").keySet());

        Set<String> recordOnly = new LinkedHashSet<>(fromRecord);
        recordOnly.removeAll(fromDocument);
        Set<String> documentOnly = new LinkedHashSet<>(fromDocument);
        documentOnly.removeAll(fromRecord);

        assertAll(
                () -> assertTrue(fromRecord.contains(VERDICT_COMPONENT),
                        VERDICT_COMPONENT + " is a component of the response record, which is the "
                                + "authoritative side of this contract"),
                () -> assertTrue(fromDocument.contains(VERDICT_COMPONENT),
                        VERDICT_COMPONENT + " is a property of schema " + ASSESSMENT_SCHEMA
                                + "; the response record is authoritative and declares it"),
                () -> assertEquals(Set.of(), recordOnly,
                        "every component of the response record is a property of schema "
                                + ASSESSMENT_SCHEMA + "; the record is authoritative"),
                () -> assertEquals(Set.of(), documentOnly,
                        "schema " + ASSESSMENT_SCHEMA + " names no property the response record "
                                + "lacks; the record is authoritative"),
                () -> assertEquals(fromRecord, fromDocument, "the two sides name one set"));
    }

    @Test
    @DisplayName("The controller carries the REST controller annotation and one base path")
    void theControllerDeclaresItsAnnotationAndBasePath() {
        assertAll(
                () -> assertTrue(
                        FraudAssessmentController.class.isAnnotationPresent(RestController.class),
                        "the controller carries the REST controller annotation"),
                () -> assertEquals(Set.of(BASE_PATH), classMappingPaths(),
                        "the controller declares one base path"));
    }

    @Test
    @DisplayName("The controller class is not final, so a subclass proxy can wrap it")
    void theControllerClassIsNotFinal() {
        assertFalse(Modifier.isFinal(FraudAssessmentController.class.getModifiers()),
                "the controller class is not final");
    }

    @Test
    @DisplayName("The controller declares one constructor, and it takes the assessment repository")
    void theControllerDeclaresOneRepositoryConstructor() {
        Constructor<?>[] constructors = FraudAssessmentController.class.getDeclaredConstructors();
        assertEquals(1, constructors.length, "declared constructor count");
        assertEquals(List.of(FraudAssessmentRepository.class),
                Arrays.asList(constructors[0].getParameterTypes()),
                "the one constructor takes the assessment repository alone");
    }

    @Test
    @DisplayName("No class, constructor, field or method of the controller carries the injection "
            + "annotation")
    void theControllerCarriesNoInjectionAnnotation() {
        List<String> carriers = new ArrayList<>();
        if (FraudAssessmentController.class.isAnnotationPresent(Autowired.class)) {
            carriers.add(FraudAssessmentController.class.getSimpleName());
        }
        for (Constructor<?> constructor : FraudAssessmentController.class
                .getDeclaredConstructors()) {
            if (constructor.isAnnotationPresent(Autowired.class)) {
                carriers.add("constructor");
            }
        }
        for (Field field : FraudAssessmentController.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                carriers.add("field " + field.getName());
            }
        }
        for (Method method : FraudAssessmentController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Autowired.class)) {
                carriers.add("method " + method.getName());
            }
        }
        assertEquals(List.of(), carriers, "elements carrying the injection annotation");
    }

    @Test
    @DisplayName("Neither the controller class nor any of its methods carries the transaction "
            + "annotation")
    void theControllerCarriesNoTransactionAnnotation() {
        List<String> carriers = new ArrayList<>();
        if (FraudAssessmentController.class.isAnnotationPresent(Transactional.class)) {
            carriers.add(FraudAssessmentController.class.getSimpleName());
        }
        for (Method method : FraudAssessmentController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Transactional.class)) {
                carriers.add("method " + method.getName());
            }
        }
        assertEquals(List.of(), carriers, "elements carrying the transaction annotation");
    }

    @Test
    @DisplayName("No controller method carries a write mapping, and no request mapping names a write "
            + "method")
    void theControllerAnswersReadsAlone() {
        List<String> writes = new ArrayList<>();
        for (Method method : FraudAssessmentController.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(PostMapping.class)
                    || method.isAnnotationPresent(PutMapping.class)
                    || method.isAnnotationPresent(PatchMapping.class)
                    || method.isAnnotationPresent(DeleteMapping.class)) {
                writes.add("mapping on " + method.getName());
            }
            writes.addAll(writeMethodsNamedBy(method.getAnnotation(RequestMapping.class),
                    method.getName()));
        }
        writes.addAll(writeMethodsNamedBy(
                FraudAssessmentController.class.getAnnotation(RequestMapping.class),
                FraudAssessmentController.class.getSimpleName()));
        assertEquals(List.of(), writes, "declarations naming a write method");
    }

    @Test
    @DisplayName("Three methods carry a read mapping, and one of them declares the path template")
    void theReadMappingsDeclareOnePathTemplate() {
        List<Method> mapped = readMappedMethods();
        List<Set<String>> declaredPaths =
                mapped.stream().map(method -> mappingPaths(method.getAnnotation(GetMapping.class)))
                        .toList();
        long withPath = declaredPaths.stream().filter(paths -> !paths.isEmpty()).count();
        assertAll(
                () -> assertEquals(2, mapped.size(), "methods carrying a read mapping"),
                () -> assertEquals(1, withPath, "read mappings declaring a path value"),
                () -> assertTrue(declaredPaths.contains(Set.of(ITEM_PATH_TEMPLATE)),
                        "one read mapping declares the path template " + ITEM_PATH_TEMPLATE),
                () -> assertEquals(List.of(), mapped.stream()
                                .filter(method -> method.getAnnotation(GetMapping.class)
                                        .params().length > 0)
                                .map(Method::getName)
                                .toList(),
                        "no read mapping is selected by a query parameter, so every request reaches "
                                + "a method src/main/resources/openapi.yaml describes"));
    }

    @Test
    @DisplayName("No mapping value carries a versioning or gateway prefix")
    void noMappingValueCarriesABannedPrefix() {
        List<String> offenders = new ArrayList<>();
        for (String declared : allMappingPaths()) {
            for (String banned : BANNED_MAPPING_PREFIXES) {
                if (declared.startsWith(banned)) {
                    offenders.add(declared);
                }
            }
        }
        assertEquals(List.of(), offenders, "mapping values carrying a banned prefix");
    }

    @Test
    @DisplayName("The path variable is constrained to sixteen characters and to no digit expression")
    void thePathVariableFixesItsWidthWithoutADigitExpression() throws NoSuchMethodException {
        Annotation[] declared = FraudAssessmentController.class
                .getDeclaredMethod("assessmentOfTransaction", String.class)
                .getParameterAnnotations()[0];
        Size width = annotationOfType(declared, Size.class);
        String rendered = renderAll(declared);
        assertAll(
                () -> assertNotNull(annotationOfType(declared, PathVariable.class),
                        "the parameter is bound from the path"),
                () -> assertNotNull(annotationOfType(declared, NotBlank.class),
                        "the parameter rejects blank text"),
                () -> assertNotNull(width, "the parameter carries a width constraint"),
                () -> assertEquals(TRANSACTION_ID_WIDTH, width.min(), "the constrained lower width"),
                () -> assertEquals(TRANSACTION_ID_WIDTH, width.max(), "the constrained upper width"),
                () -> assertNull(annotationOfType(declared, Pattern.class),
                        "the runtime refuses blank text through @NotBlank, so no expression "
                                + "constrains the characters of the parameter"),
                () -> assertEquals(List.of(), fragmentsIn(rendered, DIGIT_PATTERN_FRAGMENTS),
                        "digit expressions applied to the transaction identifier"));
    }

    @Test
    @DisplayName("The account parameter is required and constrained to eleven digits")
    void theAccountParameterFixesElevenDigits() throws NoSuchMethodException {
        Annotation[] declared = FraudAssessmentController.class
                .getDeclaredMethod("assessmentsOfAccount", String.class, String.class,
                        String.class, String.class, String.class)
                .getParameterAnnotations()[0];
        RequestParam bound = annotationOfType(declared, RequestParam.class);
        Pattern shape = annotationOfType(declared, Pattern.class);
        assertAll(
                () -> assertNotNull(bound, "the parameter is bound from the query"),
                () -> assertTrue(bound.required(), "the parameter is required"),
                () -> assertNotNull(annotationOfType(declared, NotBlank.class),
                        "the parameter rejects blank text"),
                () -> assertNotNull(shape, "the parameter carries an expression constraint"),
                () -> assertEquals(ACCOUNT_ID_REGEX, shape.regexp(), "the constrained shape"));
    }

    @Test
    @DisplayName("No controller method returns the stored entity, whole or as a type argument")
    void noControllerMethodReturnsTheStoredEntity() {
        String entityName = "FraudAssessmentEntity";
        List<String> offenders = new ArrayList<>();
        for (Method method : FraudAssessmentController.class.getDeclaredMethods()) {
            if (method.getGenericReturnType().getTypeName().contains(entityName)) {
                offenders.add(method.getName());
            }
        }
        assertEquals(List.of(), offenders, "methods returning " + entityName);
    }

    @Test
    @DisplayName("Every paging bound in the controller source is a named constant, and the page "
            + "request receives none")
    void theControllerSourceHoldsNoNumericLiteralAtAnAnnotationSite() {
        List<String> offenders = new ArrayList<>();
        for (int index = 0; index < controllerLines.size(); index++) {
            String line = controllerLines.get(index).strip();
            boolean isAnnotation = line.startsWith("@");
            // Limit.of is where the row count reaches the query, as PageRequest.of once was. A
            // literal here would be a page bound that no constant names and no test can read.
            boolean isRowCount = line.contains("Limit.of(") || line.contains("PageRequest.of(");
            if ((isAnnotation || isRowCount) && line.chars().anyMatch(Character::isDigit)) {
                offenders.add(CONTROLLER_FILE_NAME + ":" + (index + 1));
            }
        }
        assertEquals(List.of(), offenders,
                "annotation sites and row-count limits holding a numeric literal");
    }

    @Test
    @DisplayName("The controller source declares one anchored expression, and declares it as a "
            + "private constant")
    void theControllerSourceDeclaresOneAnchoredExpression() {
        List<String> declarations = new ArrayList<>();
        for (String line : controllerLines) {
            if (line.contains("\"^")) {
                declarations.add(line.strip());
            }
        }
        assertEquals(1, declarations.size(), "lines holding an anchored expression: " + declarations);
        assertTrue(declarations.get(0).startsWith("private static final String"),
                "the anchored expression is declared as a private constant: " + declarations.get(0));
        assertTrue(declarations.get(0).contains(ACCOUNT_ID_REGEX),
                "the declared expression is the account identifier shape");
    }

    @Test
    @DisplayName("The description names this service, one demonstration server and one tag")
    void theDescriptionNamesItsServiceServerAndTag() {
        Map<String, Object> info = asMap(document.get("info"), "info");
        List<Object> servers = asList(document.get("servers"), "servers");
        List<Object> tags = asList(document.get("tags"), "tags");
        assertAll(
                () -> assertEquals("CardDemo Fraud Detection Service API", info.get("title"),
                        "the published title"),
                () -> assertEquals("1.0.0", info.get("version"), "the published version"),
                () -> assertEquals(1, servers.size(), "declared servers"),
                () -> assertEquals("http://localhost:8083",
                        asMap(servers.get(0), "servers/0").get("url"), "the demonstration address"),
                () -> assertEquals(1, tags.size(), "declared tags"),
                () -> assertEquals("FraudAssessments", asMap(tags.get(0), "tags/0").get("name"),
                        "the one tag"));
    }

    @Test
    @DisplayName("The description carries no webhook, callback, extension key or external reference")
    void theDescriptionCarriesNoOptionalConstruct() {
        Set<String> keys = allKeys(document);
        List<String> present = new ArrayList<>(BANNED_DOCUMENT_KEYS.stream()
                .filter(keys::contains).toList());
        present.addAll(keys.stream().filter(key -> key.startsWith(EXTENSION_PREFIX)).toList());
        assertEquals(List.of(), present, "optional constructs present in the description");
    }

    @Test
    @DisplayName("Each path declares the read operation alone")
    void eachPathDeclaresTheReadOperationAlone() {
        Map<String, Object> paths = paths();
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paths.entrySet()) {
            Set<String> declared = asMap(entry.getValue(), entry.getKey()).keySet();
            for (String banned : BANNED_OPERATIONS) {
                if (declared.contains(banned)) {
                    offenders.add(entry.getKey() + " declares " + banned);
                }
            }
            if (!declared.contains("get")) {
                offenders.add(entry.getKey() + " declares no read operation");
            }
        }
        assertEquals(List.of(), offenders, "operations beyond the read operation");
    }

    @Test
    @DisplayName("No operation declares a fallback response or an excluded status")
    void noOperationDeclaresAFallbackResponse() {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paths().entrySet()) {
            Set<String> declared = responseKeys(entry.getKey());
            for (String banned : BANNED_RESPONSE_KEYS) {
                if (declared.contains(banned)) {
                    offenders.add(entry.getKey() + " declares " + banned);
                }
            }
            for (String key : declared) {
                if (!key.matches("[0-9]{3}")) {
                    offenders.add(entry.getKey() + " declares the non-numeric response " + key);
                }
            }
        }
        assertEquals(List.of(), offenders, "fallback and excluded responses");
    }

    @Test
    @DisplayName("The risk score is a whole number and carries no fractional format")
    void theRiskScoreIsAWholeNumber() {
        Map<String, Object> score = assessmentProperty("riskScore");
        assertAll(
                () -> assertEquals("integer", score.get("type"), "the declared type"),
                () -> assertFalse(score.containsKey("format"),
                        "the risk score declares no format"));
    }

    @Test
    @DisplayName("The rule list holds text items and declares no upper bound on its length")
    void theRuleListDeclaresNoUpperBound() {
        Map<String, Object> rules = assessmentProperty("triggeredRules");
        assertAll(
                () -> assertEquals("array", rules.get("type"), "the declared type"),
                () -> assertEquals("string", asMap(rules.get("items"), "triggeredRules items")
                        .get("type"), "the declared item type"),
                () -> assertFalse(rules.containsKey("maxItems"),
                        "the rule list declares no upper bound, so a fourth rule stays compatible"));
    }

    @Test
    @DisplayName("The transaction identifier property constrains its width and refuses blank text")
    void theTransactionIdentifierPropertyRefusesBlankText() {
        Map<String, Object> identifier = assessmentProperty("transactionId");
        String pattern = String.valueOf(identifier.get("pattern"));
        assertAll(
                () -> assertEquals("string", identifier.get("type"), "the declared type"),
                () -> assertEquals(List.of(), fragmentsIn(pattern, DIGIT_PATTERN_FRAGMENTS),
                        "the field holds text, so no digit expression constrains its characters"),
                () -> assertFalse(" ".repeat(TRANSACTION_ID_WIDTH).matches(pattern),
                        "sixteen spaces satisfy the width and are refused by the runtime @NotBlank, "
                                + "so the published shape refuses them too"),
                () -> assertTrue("0000000000683580".matches(pattern),
                        "a stored identifier satisfies the published shape"));
    }

    @Test
    @DisplayName("The account identifier property constrains eleven digits")
    void theAccountIdentifierPropertyConstrainsElevenDigits() {
        assertEquals(ACCOUNT_ID_REGEX, assessmentProperty("accountId").get("pattern"),
                "the published account identifier shape");
    }

    @Test
    @DisplayName("The problem schema closes on four text and number members")
    void theProblemSchemaClosesOnFourMembers() {
        Map<String, Object> problem = schema(PROBLEM_SCHEMA);
        Map<String, Object> properties =
                asMap(problem.get("properties"), PROBLEM_SCHEMA + " properties");
        assertAll(
                () -> assertEquals("object", problem.get("type"), "the declared type"),
                () -> assertEquals(List.of("type", "title", "status", "detail"),
                        problem.get("required"), "the required members"),
                () -> assertEquals(Set.of("type", "title", "status", "detail"), properties.keySet(),
                        "the declared members"),
                () -> assertEquals("string", asMap(properties.get("type"), "type").get("type"),
                        "the problem type member"),
                () -> assertEquals("string", asMap(properties.get("title"), "title").get("type"),
                        "the title member"),
                () -> assertEquals("string", asMap(properties.get("detail"), "detail").get("type"),
                        "the detail member"),
                () -> assertEquals("integer", asMap(properties.get("status"), "status").get("type"),
                        "the status member"));
    }

    @Test
    @DisplayName("Every schema refuses a property it does not declare")
    void everySchemaRefusesAnUndeclaredProperty() {
        Map<String, Object> schemas = asMap(nodeAt("components", "schemas"), "components/schemas");
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, Object> entry : schemas.entrySet()) {
            Object closed = asMap(entry.getValue(), entry.getKey()).get("additionalProperties");
            if (!Boolean.FALSE.equals(closed)) {
                offenders.add(entry.getKey());
            }
        }
        assertAll(
                () -> assertFalse(schemas.isEmpty(), "the description declares schemas"),
                () -> assertEquals(List.of(), offenders,
                        "schemas admitting an undeclared property"));
    }

    @Test
    @DisplayName("Every reference points inside the description and resolves to a declared node")
    void everyReferenceResolvesInsideTheDescription() {
        List<String> references = new ArrayList<>();
        collectReferences(document, references);
        List<String> offenders = new ArrayList<>();
        for (String reference : references) {
            if (reference.startsWith(NETWORK_SCHEME) || !reference.startsWith(REFERENCE_PREFIX)) {
                offenders.add(reference + " points outside the description");
            } else if (!resolves(reference)) {
                offenders.add(reference + " resolves to no declared node");
            }
        }
        assertAll(
                () -> assertFalse(references.isEmpty(), "the description carries references"),
                () -> assertEquals(List.of(), offenders, "unresolved and external references"));
    }

    @Test
    @DisplayName("Every property, parameter and template variable is named in camel case")
    void everyEnumeratedNameIsCamelCase() {
        Set<String> names = new LinkedHashSet<>();
        Map<String, Object> schemas = asMap(nodeAt("components", "schemas"), "components/schemas");
        for (Map.Entry<String, Object> entry : schemas.entrySet()) {
            names.addAll(asMap(asMap(entry.getValue(), entry.getKey()).get("properties"),
                    entry.getKey() + " properties").keySet());
        }
        for (Map.Entry<String, Object> entry : paths().entrySet()) {
            names.addAll(templateVariablesIn(entry.getKey()));
            Object declared = asMap(asMap(entry.getValue(), entry.getKey()).get("get"),
                    entry.getKey() + " get").get("parameters");
            for (Object parameter : asList(declared, entry.getKey() + " parameters")) {
                Map<String, Object> read = asMap(parameter, "parameter");
                // A header parameter is excluded, and only a header parameter. Camel case is the
                // convention for a property, a path variable and a query parameter, and Title-Case
                // with hyphens is the convention for a header field: X-Fraud-Cursor reads the way
                // Content-Type does. The convention headers do follow is asserted separately by
                // everyHeaderParameterReadsAsAHeaderField, so nothing here goes unchecked.
                if (!"header".equals(read.get("in"))) {
                    names.add(String.valueOf(read.get("name")));
                }
            }
        }
        List<String> offenders = names.stream().filter(name -> name.contains("_")
                || Character.isUpperCase(name.charAt(0))).toList();
        assertAll(
                () -> assertFalse(names.isEmpty(), "the description enumerates names"),
                () -> assertEquals(List.of(), offenders, "names outside camel case"));
    }

    /**
     * Every header parameter reads as a header field, and every one is an extension header.
     *
     * <p>This is the other half of {@link #everyEnumeratedNameIsCamelCase}, which excludes header
     * parameters because their naming convention is not camel case. Excluding them without checking
     * them would leave a name shape unasserted.
     */
    @Test
    @DisplayName("Every header parameter reads as a hyphenated extension header field")
    void everyHeaderParameterReadsAsAHeaderField() {
        List<String> headers = new ArrayList<>();
        for (Map.Entry<String, Object> entry : paths().entrySet()) {
            Object declared = asMap(asMap(entry.getValue(), entry.getKey()).get("get"),
                    entry.getKey() + " get").get("parameters");
            for (Object parameter : asList(declared, entry.getKey() + " parameters")) {
                Map<String, Object> read = asMap(parameter, "parameter");
                if ("header".equals(read.get("in"))) {
                    headers.add(String.valueOf(read.get("name")));
                }
            }
        }

        assertFalse(headers.isEmpty(), "the document declares a header parameter");
        List<String> offenders = headers.stream()
                .filter(name -> !HEADER_FIELD_NAME.matcher(name).matches())
                .toList();
        assertEquals(List.of(), offenders,
                "header names outside the X-Title-Case shape a header field takes: " + offenders);
    }

    @Test
    @DisplayName("No path names an operational endpoint")
    void noPathNamesAnOperationalEndpoint() {
        List<String> offenders = new ArrayList<>();
        for (String path : paths().keySet()) {
            String folded = path.toLowerCase(Locale.ROOT);
            for (String fragment : OPERATIONAL_FRAGMENTS) {
                if (folded.contains(fragment)) {
                    offenders.add(path + " names " + fragment);
                }
            }
        }
        assertEquals(List.of(), offenders, "paths naming an operational endpoint");
    }

    @Test
    @DisplayName("No source of this service references a sibling service package")
    void noSourceReferencesASiblingServicePackage() {
        List<String> offenders = new ArrayList<>();
        for (String leaf : FOREIGN_SERVICE_LEAVES) {
            String reference = PACKAGE_ROOT + leaf;
            for (String occurrence : occurrencesInFraudSources(reference)) {
                offenders.add(reference + " at " + occurrence);
            }
        }
        assertEquals(List.of(), offenders, "references to a sibling service package");
    }

    @Test
    @DisplayName("No source of this service holds an outbound client type")
    void noSourceHoldsAnOutboundClientType() {
        List<String> offenders = new ArrayList<>();
        for (String client : OUTBOUND_CLIENT_TYPES) {
            for (String occurrence : occurrencesInFraudSources(client)) {
                offenders.add(client + " at " + occurrence);
            }
        }
        assertEquals(List.of(), offenders, "outbound client types held by this service");
    }

    @Test
    @DisplayName("One source declares a controller type, and it is the assessment controller")
    void oneSourceDeclaresAControllerType() {
        List<String> restControllers = distinctFiles(occurrencesInFraudSources(
                REST_CONTROLLER_MARKER).stream()
                .filter(occurrence -> !occurrence.contains(ADVICE_FILE_NAME))
                .toList());
        List<String> plainControllers = distinctFiles(occurrencesInFraudSources(
                CONTROLLER_MARKER).stream()
                .filter(occurrence -> !occurrence.contains(ADVICE_FILE_NAME))
                .toList());
        assertAll(
                () -> assertEquals(List.of(CONTROLLER_FILE_NAME), restControllers,
                        "sources declaring a REST controller type"),
                () -> assertEquals(List.of(), plainControllers,
                        "sources declaring a plain controller type"));
    }

    /**
     * Asserts this service declares one advice, and that it names no base package.
     *
     * <p>The advice shapes the error body of the two read routes, which is why the marker it carries
     * reads like the controller marker and is excluded from the count above: an advice serves no
     * route of its own.
     *
     * <p>It named {@code com.carddemo.fraud.api} until a paused datastore was measured against all six
     * services. Scoping was neither what broke the health poll nor what fixed it. This service was
     * scoped throughout and still answered a paused datastore with the framework body, because
     * {@code config/ReadinessHealthConfig} let its outbox read throw: the actuator then had no document
     * to render and the request left through the error path. The guarded indicator is the fix, and with
     * it no failure of the management port reaches an advice at all.
     *
     * <p>Scoping also costs something. Spring selects an advice by the type of the handler it resolved,
     * and a request matching no mapping resolves none, so a scoped advice is skipped for exactly the
     * failures raised before a handler is chosen. On a route declaring {@code consumes} that turns the
     * documented answer to an unsupported media type back into the framework body, which is what the
     * authorization service measured. Every one of the six now names no base package, and
     * {@code equivalence-tests} {@code ApiSurfaceSecurityContractTest} holds the indicators to
     * catching instead.
     */
    @Test
    @DisplayName("The one advice of this service names no base package")
    void theOneAdviceNamesNoBasePackage() {
        List<String> advices = distinctFiles(occurrencesInFraudSources(ADVICE_MARKER));

        assertEquals(List.of(ADVICE_FILE_NAME), advices, "sources declaring an advice type");
        assertEquals(List.of(),
                List.of(FraudApiExceptionHandler.class.getAnnotation(
                                org.springframework.web.bind.annotation.RestControllerAdvice.class)
                        .basePackages()),
                "the advice names no package, so it shapes a failure raised before a handler is "
                        + "resolved as well as one raised inside it");
    }

    @Test
    @DisplayName("No mapping value names a scoring, replay or reset action")
    void noMappingValueNamesAnAction() {
        List<String> offenders = new ArrayList<>();
        for (String declared : allMappingPaths()) {
            String folded = declared.toLowerCase(Locale.ROOT);
            for (String action : BANNED_MAPPING_ACTIONS) {
                if (folded.contains(action)) {
                    offenders.add(declared + " names " + action);
                }
            }
        }
        assertEquals(List.of(), offenders, "mapping values naming an action");
    }

    /**
     * Returns the record describing one assessment.
     *
     * <p>The controller nests three records, and the set is closed by
     * {@link #theControllerNestsOnlyTheThreeRecordsItsAnswerNeeds}. This helper names the one the
     * assessment tests are about, rather than taking whichever record happens to be declared first:
     * a reflective order is not a contract, and a helper that trusted it would start asserting
     * against a different record the moment one was added.
     *
     * @return the nested assessment record
     */
    private static Class<?> responseRecord() {
        return nestedRecordNamed("FraudAssessment");
    }

    /**
     * Returns one record nested in the controller by simple name.
     *
     * @param simpleName the record to return
     * @return that record
     */
    private static Class<?> nestedRecordNamed(String simpleName) {
        return Arrays.stream(FraudAssessmentController.class.getDeclaredClasses())
                .filter(Class::isRecord)
                .filter(nested -> simpleName.equals(nested.getSimpleName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "the controller nests no record named " + simpleName));
    }

    /**
     * Returns the path values the controller declares on its class mapping.
     *
     * @return the declared base paths
     */
    private static Set<String> classMappingPaths() {
        RequestMapping mapping =
                FraudAssessmentController.class.getAnnotation(RequestMapping.class);
        assertNotNull(mapping, "the controller carries a class mapping");
        Set<String> declared = new LinkedHashSet<>(Arrays.asList(mapping.value()));
        declared.addAll(Arrays.asList(mapping.path()));
        return declared;
    }

    /**
     * Returns the path values one read mapping declares, taking both aliases.
     *
     * @param mapping the read mapping to read
     * @return the declared paths, empty when the mapping declares none
     */
    private static Set<String> mappingPaths(GetMapping mapping) {
        Set<String> declared = new LinkedHashSet<>(Arrays.asList(mapping.value()));
        declared.addAll(Arrays.asList(mapping.path()));
        return declared;
    }

    /**
     * Returns every path value the controller declares, on its class and on its methods.
     *
     * @return the declared paths
     */
    private static Set<String> allMappingPaths() {
        Set<String> declared = new LinkedHashSet<>(classMappingPaths());
        for (Method method : readMappedMethods()) {
            declared.addAll(mappingPaths(method.getAnnotation(GetMapping.class)));
        }
        return declared;
    }

    /**
     * Returns the controller methods carrying a read mapping, ordered by name.
     *
     * @return the read-mapped methods
     */
    private static List<Method> readMappedMethods() {
        return Arrays.stream(FraudAssessmentController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .sorted((left, right) -> left.getName().compareTo(right.getName())).toList();
    }

    /**
     * Returns the write methods one request mapping names.
     *
     * @param mapping the mapping to read, or {@code null} when the element carries none
     * @param owner   the element the mapping sits on, used in a failure message
     * @return one entry per write method named, empty when the mapping names none
     */
    private static List<String> writeMethodsNamedBy(RequestMapping mapping, String owner) {
        if (mapping == null) {
            return List.of();
        }
        List<RequestMethod> writes = List.of(RequestMethod.POST, RequestMethod.PUT,
                RequestMethod.PATCH, RequestMethod.DELETE);
        List<String> named = new ArrayList<>();
        for (RequestMethod declared : mapping.method()) {
            if (writes.contains(declared)) {
                named.add(owner + " names " + declared);
            }
        }
        return named;
    }

    /**
     * Returns the annotation of one type among those declared on an element.
     *
     * @param <A>         the annotation type
     * @param annotations the annotations declared on the element
     * @param type        the annotation type to find
     * @return the annotation, or {@code null} when the element carries none of that type
     */
    private static <A extends Annotation> A annotationOfType(Annotation[] annotations,
            Class<A> type) {
        for (Annotation candidate : annotations) {
            if (type.isInstance(candidate)) {
                return type.cast(candidate);
            }
        }
        return null;
    }

    /**
     * Renders every annotation of an element into one text.
     *
     * @param annotations the annotations to render
     * @return the rendered annotations, joined
     */
    private static String renderAll(Annotation[] annotations) {
        StringBuilder rendered = new StringBuilder();
        for (Annotation declared : annotations) {
            rendered.append(declared).append(' ');
        }
        return rendered.toString();
    }

    /**
     * Returns the fragments present in one text.
     *
     * @param text      the text to search
     * @param fragments the fragments to look for
     * @return the fragments found, empty when none is present
     */
    private static List<String> fragmentsIn(String text, List<String> fragments) {
        return fragments.stream().filter(text::contains).toList();
    }

    /**
     * Returns the module base directory, taking the build property first.
     *
     * @return the module base directory
     */
    private static Path resolveModuleBase() {
        String declared = System.getProperty("basedir");
        String fallback = System.getProperty("user.dir", ".");
        Path base = Path.of(declared == null || declared.isBlank() ? fallback : declared);
        if (!Files.isRegularFile(base.resolve(CONTROLLER_SOURCE))) {
            throw new AssertionError("resolved module base " + base.toAbsolutePath()
                    + " carries no " + CONTROLLER_SOURCE);
        }
        return base;
    }

    /**
     * Returns every line of every Java source under one directory that holds one text.
     *
     * <p>The directory is read and never written.
     *
     * @param root   the directory to walk
     * @param needle the text to look for
     * @return one entry per occurrence, each naming the file and the line, counting from one
     */
    private static List<String> occurrencesUnder(Path root, String needle) {
        if (!Files.isDirectory(root)) {
            throw new AssertionError("scan root " + root.toAbsolutePath() + " is no directory");
        }
        List<String> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> sources = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(JAVA_SUFFIX)).sorted().toList();
            for (Path source : sources) {
                List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    if (lines.get(index).contains(needle)) {
                        found.add(source.getFileName() + ":" + (index + 1));
                    }
                }
            }
        } catch (IOException failure) {
            throw new AssertionError("cannot read Java sources under " + root.toAbsolutePath(),
                    failure);
        }
        return found;
    }

    /**
     * Returns every occurrence of one text across the production and test sources of this service.
     *
     * @param needle the text to look for
     * @return one entry per occurrence, each naming the file and the line
     */
    private static List<String> occurrencesInFraudSources(String needle) {
        List<String> found = new ArrayList<>(
                occurrencesUnder(moduleBase.resolve(MAIN_SOURCE_ROOT), needle));
        found.addAll(occurrencesUnder(moduleBase.resolve(TEST_SOURCE_ROOT), needle));
        return found;
    }

    /**
     * Returns the distinct file names among a list of occurrences, ordered.
     *
     * @param occurrences the occurrences to reduce
     * @return the distinct file names
     */
    private static List<String> distinctFiles(List<String> occurrences) {
        return occurrences.stream().map(occurrence ->
                occurrence.substring(0, occurrence.lastIndexOf(':'))).distinct().sorted().toList();
    }

    /**
     * Returns the paths the description declares.
     *
     * @return the path items, keyed by path
     */
    private static Map<String, Object> paths() {
        return asMap(document.get("paths"), "paths");
    }

    /**
     * Returns one schema the description declares.
     *
     * @param name the schema name
     * @return the schema
     */
    private static Map<String, Object> schema(String name) {
        return asMap(asMap(nodeAt("components", "schemas"), "components/schemas").get(name),
                "schema " + name);
    }

    /**
     * Returns one property of the assessment schema.
     *
     * @param name the property name
     * @return the property
     */
    private static Map<String, Object> assessmentProperty(String name) {
        return asMap(asMap(schema(ASSESSMENT_SCHEMA).get("properties"),
                ASSESSMENT_SCHEMA + " properties").get(name), ASSESSMENT_SCHEMA + "/" + name);
    }

    /**
     * Returns the response keys the read operation of one path declares.
     *
     * @param path the path to read
     * @return the declared response keys
     */
    private static Set<String> responseKeys(String path) {
        Map<String, Object> operation =
                asMap(asMap(paths().get(path), path).get("get"), path + " get");
        return asMap(operation.get("responses"), path + " responses").keySet();
    }

    /**
     * Returns the node at one key path of the description.
     *
     * @param keys the keys to follow, outermost first
     * @return the node found
     */
    private static Object nodeAt(String... keys) {
        Object current = document;
        StringBuilder walked = new StringBuilder();
        for (String key : keys) {
            current = asMap(current, walked.isEmpty() ? DOCUMENT_RESOURCE : walked.toString())
                    .get(key);
            walked.append('/').append(key);
        }
        return current;
    }

    /**
     * Returns every key the description carries, at every depth.
     *
     * @param node the node to walk
     * @return the keys found
     */
    private static Set<String> allKeys(Object node) {
        Set<String> keys = new LinkedHashSet<>();
        collectKeys(node, keys);
        return keys;
    }

    /**
     * Adds every key of one node, and of its children, to a set.
     *
     * @param node the node to walk
     * @param keys the set to add to
     */
    private static void collectKeys(Object node, Set<String> keys) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                collectKeys(entry.getValue(), keys);
            }
        } else if (node instanceof List<?> items) {
            for (Object item : items) {
                collectKeys(item, keys);
            }
        }
    }

    /**
     * Adds every reference value of one node, and of its children, to a list.
     *
     * @param node       the node to walk
     * @param references the list to add to
     */
    private static void collectReferences(Object node, List<String> references) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if (REFERENCE_KEY.equals(String.valueOf(entry.getKey()))) {
                    references.add(String.valueOf(entry.getValue()));
                }
                collectReferences(entry.getValue(), references);
            }
        } else if (node instanceof List<?> items) {
            for (Object item : items) {
                collectReferences(item, references);
            }
        }
    }

    /**
     * Reports whether one internal reference reaches a declared node.
     *
     * @param reference the reference to follow
     * @return {@code true} when every segment resolves
     */
    private static boolean resolves(String reference) {
        Object current = document;
        for (String segment : reference.substring(REFERENCE_PREFIX.length()).split("/")) {
            if (!(current instanceof Map<?, ?> mapping) || !mapping.containsKey(segment)) {
                return false;
            }
            current = mapping.get(segment);
        }
        return current != null;
    }

    /**
     * Returns the template variables one path names.
     *
     * @param path the path to read
     * @return the variable names, empty when the path names none
     */
    private static List<String> templateVariablesIn(String path) {
        List<String> variables = new ArrayList<>();
        int open = path.indexOf('{');
        while (open >= 0) {
            int close = path.indexOf('}', open);
            if (close < 0) {
                throw new AssertionError("path " + path + " opens a template variable and "
                        + "never closes it");
            }
            variables.add(path.substring(open + 1, close));
            open = path.indexOf('{', close);
        }
        return variables;
    }

    /**
     * Returns one node as a mapping.
     *
     * @param value the node to read
     * @param what  the node name, used in a failure message
     * @return the node as a mapping
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?>)) {
            throw new AssertionError(what + " reads as no mapping");
        }
        return (Map<String, Object>) value;
    }

    /**
     * Returns one node as a sequence.
     *
     * @param value the node to read
     * @param what  the node name, used in a failure message
     * @return the node as a sequence
     */
    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value, String what) {
        if (!(value instanceof List<?>)) {
            throw new AssertionError(what + " reads as no sequence");
        }
        return (List<Object>) value;
    }
}
