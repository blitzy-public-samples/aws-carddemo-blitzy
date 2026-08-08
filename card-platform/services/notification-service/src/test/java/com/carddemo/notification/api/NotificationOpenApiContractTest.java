package com.carddemo.notification.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.cobol.PanMasker;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Holds {@code src/main/resources/openapi.yaml} to the Java contract it describes.
 *
 * <p>No generator produces the document, so a test is what keeps it truthful. Each group below
 * reads the packaged classpath resource and compares one part of it against the records of this
 * package. The comparisons also read {@link NotificationHistoryController} and the field widths of
 * {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20-L36}.
 *
 * <p>The envelope total traces to {@code WS-TOTAL-AMT PIC S9(9)V99} at
 * {@code app/cbl/CBSTM03A.CBL:L65}, declared under {@code 01 COMP3-VARIABLES COMP-3.} at
 * {@code app/cbl/CBSTM03A.CBL:L64}. Nine integer digits bounds every monetary shape the document
 * declares.
 *
 * <p>Two source positions reach no property of the item schema. The card identity sits on the
 * response envelope, so the item declares no card token. The trailing {@code FILLER PIC X(20)} at
 * {@code app/cpy/COSTM01.CPY:L36} carries no property at all.
 *
 * <p>One source behaviour is not reproduced. The copy step
 * {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at {@code app/jcl/CREASTMT.JCL:L54} fills
 * target bytes 279 through 328, so the batch read model receives twenty-four of the twenty-six
 * processing timestamp characters. The document bounds that property at twenty-six.
 *
 * <p>Two mapping facts belong in {@code card-platform/docs/traceability-matrix.md}.
 * <ul>
 *   <li>{@code description} is bounded at {@value #DESCRIPTION_WIDTH} characters, while the
 *       renderer truncates into the {@value #RENDERED_DESCRIPTION_WIDTH}-character field declared
 *       at {@code app/cbl/CBSTM03A.CBL:L135} and filled at
 *       {@code app/cbl/CBSTM03A.CBL:L677}.</li>
 *   <li>The twenty-four-of-twenty-six copy at {@code app/jcl/CREASTMT.JCL:L54} is not
 *       reproduced.</li>
 * </ul>
 *
 * <p>The test starts no server, opens no database, and reaches no broker. It reads one classpath
 * resource.
 */
class NotificationOpenApiContractTest {

    /** The classpath name of the interface description. */
    private static final String RESOURCE = "/openapi.yaml";

    /** The version of the specification the document declares. */
    private static final String SPECIFICATION_VERSION = "3.1.0";

    /** The version the document gives itself, distinct from the specification version. */
    private static final String DOCUMENT_VERSION = "1.0.0";

    /** Port the published mapping of the demo stack answers on. From AAP section 0.5.1. */
    private static final String HOST_PORT = "8084";

    /** Port the container itself listens on. From AAP section 0.5.1. */
    private static final String CONTAINER_PORT = "8080";

    /** Prefix every reference in the document carries, so no reference leaves the file. */
    private static final String LOCAL_REFERENCE_PREFIX = "#/";

    /** Shape of a masked card number: twelve mask characters then the last four digits. */
    private static final String MASKED_CARD_NUMBER_PATTERN = "^\\*{12}[0-9]{4}$";

    /**
     * Shape of a monetary value: an optional minus, up to nine integer digits, then two fractional
     * digits. From {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} and
     * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}.
     */
    private static final String MONEY_PATTERN = "^-?\\d{1,9}\\.\\d{2}$";

    /** Shape of the category code, four digits. From {@code app/cpy/COSTM01.CPY:L26}. */
    private static final String CATEGORY_CODE_PATTERN = "^[0-9]{4}$";

    /** Shape of the merchant identifier, nine digits. From {@code app/cpy/COSTM01.CPY:L30}. */
    private static final String MERCHANT_ID_PATTERN = "^[0-9]{9}$";

    /**
     * Shape of the origin timestamp: a space between the day and the hour, colons between the time
     * parts, and six fractional digits. From {@code TRNX-ORIG-TS PIC X(26)} at
     * {@code app/cpy/COSTM01.CPY:L34}.
     */
    private static final String ORIGIN_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{6}$";

    /**
     * Shape of the processing timestamp: a dash between the day and the hour, dots between the time
     * parts, two hundredths digits, then four zero characters. From
     * {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}.
     */
    private static final String PROCESSING_TIMESTAMP_PATTERN =
            "^\\d{4}-\\d{2}-\\d{2}-\\d{2}\\.\\d{2}\\.\\d{2}\\.\\d{2}0{4}$";

    /** Characters both timestamp properties hold. From {@code app/cpy/COSTM01.CPY:L34-L35}. */
    private static final int TIMESTAMP_WIDTH = 26;

    /** Characters the description property holds. From {@code app/cpy/COSTM01.CPY:L28}. */
    private static final int DESCRIPTION_WIDTH = 100;

    /**
     * Characters the rendered statement line holds, at the field declared at
     * {@code app/cbl/CBSTM03A.CBL:L135}. The document bounds no property at this width.
     */
    private static final int RENDERED_DESCRIPTION_WIDTH = 49;

    /** Integer digits a monetary shape admits. From {@code app/cbl/CBSTM03A.CBL:L65}. */
    private static final int MONEY_INTEGER_DIGITS = 9;

    /** The one media type the endpoint answers with. */
    private static final String JSON_MEDIA_TYPE = "application/json";

    /** Keys of the parsed model that name a schema object. */
    private static final String PROPERTIES = "properties";

    /** Value of {@code type} that names a structured schema object. */
    private static final String OBJECT_TYPE = "object";

    /** Key naming the type of a schema. */
    private static final String TYPE = "type";

    /** Key naming a regular expression a text value satisfies. */
    private static final String PATTERN = "pattern";

    /** Key naming the upper character bound of a text value. */
    private static final String MAX_LENGTH = "maxLength";

    /** Key naming the lower character bound of a text value. */
    private static final String MIN_LENGTH = "minLength";

    /** Key naming whether a schema object admits an undeclared property. */
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    /** Key naming a reference to another schema. */
    private static final String REFERENCE = "$ref";

    /** The methods the document declares no operation for. */
    private static final List<String> WRITE_METHODS =
            List.of("post", "put", "patch", "delete", "head", "options", "trace");

    /** Parameter names the endpoint declares none of. */
    private static final List<String> OFFSET_PAGING_PARAMETERS =
            List.of("page", "size", "limit", "offset", "cursor", "sort");

    /**
     * Response keys the document declares none of.
     *
     * <p>{@code 500} left this list when the document gained the response. A caller that reads only
     * the document has to know a service fault is answerable, and the handler that produces one
     * returns the same {@code ApiError} shape every other refusal returns.
     */
    private static final List<String> UNDECLARED_STATUSES =
            List.of("409", "422", "default");

    /** Statuses the filter chain writes, as a problem document rather than as the record shape. */
    private static final List<String> SECURITY_WRITTEN_STATUSES = List.of("401", "403");

    /**
     * The statuses a filter of the chain writes, rather than the exception handler.
     *
     * <p>{@code config/SecurityConfig} writes the 401 and the 403, and
     * {@code config/RequestRateCeilingFilter} writes the 429. All three are written by hand as a
     * fixed literal, so all three carry the problem document and not the handler shape.
     */
    private static final Set<String> CHAIN_REFUSAL_STATUSES = Set.of("401", "403", "429");

    /** The four members the problem document RFC 9457 defines. */
    private static final Set<String> PROBLEM_DOCUMENT_MEMBERS =
            Set.of("type", "title", "status", "detail");

    /** Statuses that carry no body, so no schema describes them. */
    private static final List<String> BODYLESS_STATUSES = List.of("406");

    /** Media type RFC 9457 names, which the filter chain writes its refusals under. */
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";

    /** Envelope property names the document declares none of. */
    private static final List<String> OFFSET_PAGING_PROPERTIES =
            List.of("nextPage", "hasNext", "totalPages", "links");

    /**
     * Property names the account service owns. Every one sits in {@code app/cbl/CBSTM03A.CBL}.
     * <ul>
     *   <li>the name group at {@code app/cbl/CBSTM03A.CBL:L90}, value field at
     *       {@code app/cbl/CBSTM03A.CBL:L91}</li>
     *   <li>the three address groups at {@code app/cbl/CBSTM03A.CBL:L93},
     *       {@code app/cbl/CBSTM03A.CBL:L96} and {@code app/cbl/CBSTM03A.CBL:L99}</li>
     *   <li>the balance group at {@code app/cbl/CBSTM03A.CBL:L112}, value field at
     *       {@code app/cbl/CBSTM03A.CBL:L113}</li>
     *   <li>the score group at {@code app/cbl/CBSTM03A.CBL:L119}, value field at
     *       {@code app/cbl/CBSTM03A.CBL:L118}</li>
     * </ul>
     */
    private static final List<String> ACCOUNT_OWNED_PROPERTIES = List.of("accountId",
            "currentBalance", "creditLimit", "cashCreditLimit", "activeStatus", "openDate",
            "expir", "reissueDate", "groupId", "ficoScore", "customerName", "addressLine1",
            "addressLine2", "addressLine3");

    /** Property names an event envelope carries. No response restates an event. */
    private static final List<String> EVENT_ENVELOPE_PROPERTIES =
            List.of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId");

    /** Property names a delivery channel would carry. An alert is rendered and logged. */
    private static final List<String> DELIVERY_CHANNEL_PROPERTIES = List.of("channel",
            "emailAddress", "phoneNumber", "smsNumber", "deliveryStatus");

    /** Property names a card or account status would carry. */
    private static final List<String> STATUS_PROPERTIES =
            List.of("cardStatus", "accountStatus", "activeStatus");

    /** Property names that would carry a decline outcome of the authorization path. */
    private static final List<String> DECLINE_PROPERTIES =
            List.of("reason", "reasonCode", "decline", "declineReason", "reject", "rejectReason");

    /**
     * The three-letter code of the card verification value, built from the initials of its own
     * expansion so the code appears in no literal of this file. The card record declares that field
     * at {@code app/cpy/CVACT02Y.cpy:L7}, and no response, log or event carries it.
     */
    private static final String CARD_VERIFICATION_CODE = initialsOf("Card Verification Value");

    /** Property names that would name the card verification value in full. */
    private static final List<String> CARD_VERIFICATION_PROPERTIES =
            List.of(CARD_VERIFICATION_CODE, "cardVerification", "securityCode",
                    "cardVerificationValue");

    /** Shape every property name of the document satisfies. */
    private static final Pattern LOWER_CAMEL_CASE = Pattern.compile("^[a-z][A-Za-z0-9]*$");

    /** A run of sixteen digits, bounded so a longer run does not hide one. */
    private static final Pattern SIXTEEN_DIGIT_RUN =
            Pattern.compile("(?<![0-9])[0-9]{16}(?![0-9])");

    /** The interface description as text, for the scans that read the whole file. */
    private static String document;

    /** The interface description parsed once, for the groups that read its structure. */
    private static Map<String, Object> model;

    /**
     * Reads the packaged interface description from the classpath and parses it.
     *
     * <p>The document sits under {@code src/main/resources}, so the build copies it into the
     * classes output and the test classpath carries it. No path is built from a working directory
     * and nothing under {@code app/} is opened.
     *
     * @throws IOException when the resource cannot be read
     */
    @BeforeAll
    static void readTheDocument() throws IOException {
        try (InputStream stream =
                NotificationOpenApiContractTest.class.getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, "the test classpath carries the resource " + RESOURCE);
            document = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(document.isBlank(), "the resource " + RESOURCE + " carries content");
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
        model = stringKeyed(parsed, RESOURCE);
    }

    @Nested
    @DisplayName("The header names the specification, the document version and one server")
    class DocumentHeader {

        /** Asserts the specification version the document declares. */
        @Test
        void theSpecificationVersionIsDeclared() {
            assertEquals(SPECIFICATION_VERSION, textAt(model, "openapi", "the document"),
                    "the specification version the document declares");
        }

        /**
         * Asserts the version the document gives itself, and that the value stands apart from the
         * specification version the sibling test reads.
         */
        @Test
        void theDocumentCarriesItsOwnVersion() {
            String declared = textAt(mapAt(model, "info", "the document"), "version", "the info");
            assertEquals(DOCUMENT_VERSION, declared, "the version of the document");
            assertNotEquals(SPECIFICATION_VERSION, declared,
                    "the document version and the specification version are separate values");
        }

        /** Asserts the info section names the service and describes it. */
        @Test
        void theInfoSectionNamesAndDescribesTheService() {
            Map<String, Object> info = mapAt(model, "info", "the document");
            assertFalse(textAt(info, "title", "the info").isBlank(), "the info carries a title");
            assertFalse(textAt(info, "description", "the info").isBlank(),
                    "the info carries a description");
        }

        /**
         * Asserts one server entry, whose address names the published mapping
         * {@value #HOST_PORT} and whose description records the container port
         * {@value #CONTAINER_PORT}. Both values come from AAP section 0.5.1 and
         * {@code card-platform/docker-compose.yml}.
         */
        @Test
        void oneServerNamesThePublishedMappingAndTheContainerPort() {
            List<Object> servers = listAt(model, "servers", "the document");
            assertEquals(1, servers.size(), "the document declares one server, and declares "
                    + servers.size());
            Map<String, Object> server = stringKeyed(servers.get(0), "the server entry");
            String address = textAt(server, "url", "the server entry");
            assertTrue(address.contains(HOST_PORT),
                    "the address " + address + " names the published mapping " + HOST_PORT);
            assertTrue(textAt(server, "description", "the server entry").contains(CONTAINER_PORT),
                    "the server description records the container port " + CONTAINER_PORT);
        }
    }

    @Nested
    @DisplayName("One route, one read operation, one media type")
    class OneRouteOneOperation {

        /**
         * Asserts the document declares one route, and that the route reads the template
         * {@link NotificationHistoryController} maps.
         */
        @Test
        void theDocumentDeclaresTheOneRouteTheControllerMaps() {
            assertEquals(NotificationHistoryController.ROUTE_TEMPLATE, routeKey(),
                    "the route the document declares against the route the controller maps");
            assertEquals("/notifications/{cardToken}", routeKey(), "the route template");
        }

        /** Asserts the document carries no gateway prefix and no version segment. */
        @Test
        void theRouteCarriesNoPrefixAndNoVersionSegment() {
            assertFalse(document.contains("/api"), "a gateway prefix appears in the document");
            assertFalse(document.contains("/v1"), "a version segment appears in the document");
        }

        /** Asserts the route declares the read method alone, and names no write method. */
        @Test
        void theRouteDeclaresTheReadMethodAlone() {
            Map<String, Object> pathItem = mapAt(paths(), routeKey(), "the route");
            assertEquals(Set.of("get"), pathItem.keySet(), "the methods the route declares");
            for (String method : WRITE_METHODS) {
                assertFalse(pathItem.containsKey(method), "the route declares " + method);
            }
        }

        /** Asserts the successful response declares one media type. */
        @Test
        void theSuccessfulResponseDeclaresOneMediaType() {
            Map<String, Object> response = mapAt(responses(), "200", "the successful response");
            Map<String, Object> content = mapAt(response, "content", "the successful response");
            assertEquals(List.of(JSON_MEDIA_TYPE), new ArrayList<>(content.keySet()),
                    "the media types the successful response declares");
        }
    }

    @Nested
    @DisplayName("One path parameter, and no offset paging parameter")
    class ThePathParameter {

        /** Asserts the operation carries one parameter in the path. */
        @Test
        void oneParameterIsCarriedInThePath() {
            List<Map<String, Object>> inPath = parameters().stream()
                    .filter(parameter -> "path".equals(parameter.get("in")))
                    .toList();
            assertEquals(1, inPath.size(), "the operation declares one path parameter, and declares "
                    + namesOf(parameters()));
        }

        /** Asserts the path parameter names the card token and is required. */
        @Test
        void thePathParameterIsTheRequiredCardToken() {
            Map<String, Object> cardToken = pathParameter();
            assertEquals("cardToken", textAt(cardToken, "name", "the path parameter"),
                    "its name");
            assertEquals("path", textAt(cardToken, "in", "the path parameter"),
                    "where it is carried");
            assertEquals(Boolean.TRUE, cardToken.get("required"),
                    "the path parameter is required");
            assertNotEquals("cardNumber", textAt(cardToken, "name", "the path parameter"),
                    "no card number names a resource of this service");
        }

        /**
         * Asserts the path parameter is text at the card-token width, and never at the card-number
         * width and never a number. The width is {@link PanMasker#CARD_TOKEN_LENGTH}; the token stands
         * for {@code TRNX-CARD-NUM PIC X(16)} at {@code app/cpy/COSTM01.CPY:L22} and is not that
         * field, so its width is its own.
         */
        @Test
        void thePathParameterIsTextAtTheCardTokenWidth() {
            Map<String, Object> schema = mapAt(pathParameter(), "schema", "the path parameter");
            assertEquals("string", textAt(schema, TYPE, "the path parameter schema"), "its type");
            assertEquals(PanMasker.CARD_TOKEN_LENGTH,
                    intAt(schema, MIN_LENGTH, "the card-token schema"),
                    "the lower character bound of the card token");
            assertEquals(PanMasker.CARD_TOKEN_LENGTH,
                    intAt(schema, MAX_LENGTH, "the card-token schema"),
                    "the upper character bound of the card token");
            assertNotEquals(PanMasker.CARD_NUMBER_LENGTH,
                    intAt(schema, MIN_LENGTH, "the card-token schema"),
                    "a value of the card-number width is refused by the width alone");
        }

        /**
         * Asserts the declared shape is the one {@link NotificationHistoryController} enforces, so
         * the document and the constraint carry one value.
         */
        @Test
        void theDeclaredShapeIsTheOneTheControllerEnforces() {
            String declared =
                    textAt(mapAt(pathParameter(), "schema", "the path parameter"), PATTERN,
                            "the path parameter schema");
            assertEquals("^[0-9a-f]{" + PanMasker.CARD_TOKEN_LENGTH + "}$", declared,
                    "the shape the document declares against the shape a card token takes");
            assertEquals(PanMasker.CARD_TOKEN_PATTERN, declared,
                    "one declaration of that shape across the platform");
            assertEquals(NotificationHistoryController.CARD_TOKEN_PATTERN, declared,
                    "the shape the document declares against the shape the controller enforces");
        }

        /** Asserts the operation declares the path parameter alone, and no query parameter. */
        @Test
        void theOperationDeclaresThePathParameterAlone() {
            assertEquals(List.of("cardToken"), namesOf(parameters()),
                    "the parameters the operation declares");
            for (Map<String, Object> parameter : parameters()) {
                assertEquals("path", parameter.get("in"),
                        parameter.get("name") + " is carried outside the path");
            }
        }

        /** Asserts no parameter names an offset paging value. */
        @Test
        void noParameterNamesAnOffsetPagingValue() {
            List<String> declared = namesOf(parameters());
            for (String absent : OFFSET_PAGING_PARAMETERS) {
                assertFalse(declared.contains(absent),
                        "the operation declares the parameter " + absent + " among " + declared);
            }
        }
    }

    @Nested
    @DisplayName("The documented status set, and the statuses the document omits")
    class DocumentedStatuses {

        /**
         * Asserts the status keys the operation declares, normalised to text.
         *
         * <p>Four are reached before the read: a successful page, a refused cursor, a missing
         * credential and a credential without the scope. {@code 500} is the fifth, and it is the one
         * the service answers when the read itself fails. Documenting it is what lets a caller
         * distinguish a fault from a refusal without reading Java.
         */
        @Test
        void theOperationDeclaresTheDocumentedStatusSet() {
            assertEquals(
                    List.of("200", "400", "401", "403", "404", "405", "406", "429", "500"),
                    statusKeys(), "the statuses the operation documents");
        }

        /**
         * Asserts the missing-resource status is documented and carries the failure shape.
         *
         * <p>A token holding no entry answers {@code 404}. The masked card number the successful body
         * names its card by is column {@code masked_card_number} of an entry, so a card with no entry
         * has none to read and no truthful successful body exists for it. A caller reaches this route
         * only for a token it holds a {@code SCOPE_CARD_} authority for, and the chain answers
         * {@code 403} before the read otherwise, so the status discloses nothing the caller did not
         * already hold.
         */
        @Test
        void theMissingResourceStatusIsDocumentedAndCarriesTheFailureShape() {
            assertTrue(statusKeys().contains("404"),
                    "the operation documents the missing-resource status");
            Map<String, Object> schema =
                    resolve(textAt(bodySchemaOf("404"), REFERENCE, "the body of 404"));
            assertEquals(Set.copyOf(componentNamesOf(ApiErrorResponse.class)),
                    propertiesOf(schema, "the error schema").keySet(),
                    "the missing-resource answer carries the shape every refusal this endpoint "
                            + "writes carries");
            assertTrue(document.contains(NotificationHistoryController.NO_HISTORY_MESSAGE),
                    "the document publishes the text the answer carries");
        }

        /** Asserts the operation documents none of the remaining statuses. */
        @Test
        void theOperationDocumentsNoneOfTheRemainingStatuses() {
            for (String absent : UNDECLARED_STATUSES) {
                assertFalse(statusKeys().contains(absent),
                        "the operation documents the status " + absent);
            }
        }

        /**
         * Asserts every failing response the endpoint writes carries the shape
         * {@link ApiErrorResponse} declares, read as the document declares it.
         *
         * <p>Two statuses are written by the filter chain rather than by the endpoint.
         * {@code config/SecurityConfig} writes the four members RFC 9457 names, by hand and under
         * {@code application/problem+json}, so those two responses declare the {@code Problem}
         * schema. Every status the endpoint itself writes declares the record shape. A caller reads
         * one schema per status either way.
         */
        @Test
        void everyFailingResponseCarriesTheShapeItsWriterProduces() {
            Set<String> handlerShape = Set.copyOf(componentNamesOf(ApiErrorResponse.class));
            for (String status : statusKeys()) {
                if (status.startsWith("2") || SECURITY_WRITTEN_STATUSES.contains(status)
                        || BODYLESS_STATUSES.contains(status)) {
                    continue;
                }
                Map<String, Object> schema =
                        resolve(textAt(bodySchemaOf(status), REFERENCE, "the body of " + status));
                Set<String> expected = CHAIN_REFUSAL_STATUSES.contains(status)
                        ? PROBLEM_DOCUMENT_MEMBERS : handlerShape;
                assertEquals(expected, propertiesOf(schema, "the error schema").keySet(),
                        "the properties the body of " + status + " declares");
            }
        }

        /**
         * Asserts the two statuses the filter chain writes declare the problem document.
         *
         * <p>The document declared the record shape under {@code application/json} for both, while
         * {@code config/SecurityConfig} writes the four members RFC 9457 names under
         * {@code application/problem+json}. A client generated from the document parsed a body the
         * service never sends.
         */
        @Test
        void thePairTheFilterChainWritesDeclaresTheProblemDocument() {
            for (String status : SECURITY_WRITTEN_STATUSES) {
                Map<String, Object> response =
                        mapAt(responses(), status, "the response " + status);
                Map<String, Object> content =
                        mapAt(response, "content", "the response " + status);
                assertEquals(List.of(PROBLEM_MEDIA_TYPE), new ArrayList<>(content.keySet()),
                        "the media type the response " + status + " declares");
                Map<String, Object> schema = resolve(textAt(
                        mapAt(mapAt(content, PROBLEM_MEDIA_TYPE, "the response " + status),
                                "schema", "the response " + status),
                        REFERENCE, "the body of " + status));
                assertEquals(Set.of("type", "title", "status", "detail"),
                        propertiesOf(schema, "the problem schema").keySet(),
                        "the four members RFC 9457 names");
            }
        }

        /**
         * Asserts the error body declares no property that could carry a value read from the
         * request back to the caller.
         */
        @Test
        void theErrorBodyDeclaresNoPropertyThatEchoesARequestValue() {
            Set<String> declared = propertiesOf(errorSchema(), "the error schema").keySet();
            for (String absent : List.of("instance", "path", "uri", "requestUri", "detail")) {
                assertFalse(declared.contains(absent),
                        "the error body declares the property " + absent);
            }
        }
    }

    @Nested
    @DisplayName("Every schema object closes itself to an undeclared property")
    class SchemaObjectClosure {

        /**
         * Walks the whole parsed document and asserts every schema object carries
         * {@value #ADDITIONAL_PROPERTIES} set to false. Every miss is reported in one message,
         * alongside the count of schema objects the walk reached.
         */
        @Test
        void everySchemaObjectRefusesAnUndeclaredProperty() {
            Map<String, Map<String, Object>> objects = schemaObjects();
            List<String> open = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> object : objects.entrySet()) {
                if (!Boolean.FALSE.equals(object.getValue().get(ADDITIONAL_PROPERTIES))) {
                    open.add(object.getKey() + " carries "
                            + object.getValue().get(ADDITIONAL_PROPERTIES));
                }
            }
            assertEquals(List.of(), open, "the walk reached " + objects.size()
                    + " schema objects, and these admit an undeclared property: " + open);
        }

        /**
         * Asserts the walk reaches every schema object the document declares, so the sibling test
         * covers more than a pair of spot checks.
         */
        @Test
        void theWalkReachesEverySchemaObject() {
            Map<String, Map<String, Object>> objects = schemaObjects();
            assertTrue(objects.size() > 2, "the walk reached " + objects.size()
                    + " schema objects: " + objects.keySet());
            assertEquals(countOf(document, ADDITIONAL_PROPERTIES + ":"), objects.size(),
                    "the walk reached one schema object per declaration of the text");
        }

        /**
         * Asserts no schema admits an undeclared property, and none delegates the decision to a
         * further schema.
         */
        @Test
        void noSchemaAdmitsOrDelegatesAnUndeclaredProperty() {
            List<Object> declared = everyValueOf(ADDITIONAL_PROPERTIES);
            assertFalse(declared.isEmpty(), "the document declares " + ADDITIONAL_PROPERTIES);
            for (Object value : declared) {
                assertNotEquals(Boolean.TRUE, value, "a schema admits an undeclared property");
                assertFalse(value instanceof Map<?, ?>,
                        "a schema delegates " + ADDITIONAL_PROPERTIES + " to a further schema");
                assertEquals(Boolean.FALSE, value,
                        "a schema declares " + ADDITIONAL_PROPERTIES + " as " + value);
            }
        }
    }

    @Nested
    @DisplayName("The envelope: the masked display value, the count, the total and the entries")
    class TheEnvelopeSchema {

        /**
         * Asserts the envelope declares exactly the components {@link NotificationHistoryResponse}
         * carries, read by reflection, so a drift on either side fails.
         */
        @Test
        void theEnvelopePropertiesAreTheRecordComponents() {
            List<String> components = componentNamesOf(NotificationHistoryResponse.class);
            assertEquals(Set.copyOf(components),
                    propertiesOf(envelopeSchema(), "the envelope").keySet(),
                    "the properties the envelope declares against the record components");
            assertEquals(List.of("cardNumber", "transactionCount", "totalAmount",
                    "transactions"), components, "the components of the response record");
        }

        /** Asserts the envelope declares its properties in the order the record declares them. */
        @Test
        void theEnvelopeDeclaresItsPropertiesInRecordOrder() {
            assertEquals(componentNamesOf(NotificationHistoryResponse.class),
                    new ArrayList<>(propertiesOf(envelopeSchema(), "the envelope").keySet()),
                    "the order the envelope declares against the order the record declares");
        }

        /**
         * Asserts the envelope carries no card identity beyond the masked display value. The token
         * names the resource on the request line and reaches no response body: it names one card for
         * as long as its key stands, so a body carrying it would let a reader of a log follow that
         * card across every request that touched it. A caller already holds the token it asked with.
         */
        @Test
        void theEnvelopeCarriesNoCardIdentity() {
            Set<String> declared = propertiesOf(envelopeSchema(), "the envelope").keySet();
            assertFalse(declared.contains("cardToken"), "the envelope declares cardToken");
            assertEquals(List.of("cardNumber"), declared.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).contains("card"))
                            .toList(),
                    "the card-bearing properties of the envelope");
            assertFalse(everyPropertyName().contains("cardToken"),
                    "a schema of the document declares cardToken");
            assertEquals(List.of("cardToken"), namesOf(parameters()),
                    "the token names the resource, and it does so in the path alone");
        }

        /**
         * Asserts the display value carries the masked shape: twelve mask characters then the last
         * {@value PanMasker#VISIBLE_DIGIT_COUNT} digits, at the width
         * {@code TRNX-CARD-NUM PIC X(16)} declares at {@code app/cpy/COSTM01.CPY:L22}.
         */
        @Test
        void theDisplayValueCarriesTheMaskedShape() {
            Map<String, Object> masked = property(envelopeSchema(), "cardNumber", "the envelope");
            assertEquals("string", textAt(masked, TYPE, "cardNumber"), "the type of cardNumber");
            assertEquals(MASKED_CARD_NUMBER_PATTERN, textAt(masked, PATTERN, "cardNumber"),
                    "the shape of cardNumber");
            assertEquals(PanMasker.CARD_NUMBER_LENGTH, intAt(masked, MAX_LENGTH, "cardNumber"),
                    "the width of cardNumber");
        }

        /** Asserts the entry count is a whole number that never falls below zero. */
        @Test
        void theEntryCountIsAWholeNumberFromZero() {
            Map<String, Object> count =
                    property(envelopeSchema(), "transactionCount", "the envelope");
            assertEquals("integer", textAt(count, TYPE, "transactionCount"),
                    "the type of transactionCount");
            assertEquals(0, intAt(count, "minimum", "transactionCount"),
                    "the lower bound of transactionCount");
        }

        /**
         * Asserts the total travels as text at the monetary shape. The ceiling of
         * {@value #MONEY_INTEGER_DIGITS} integer digits comes from
         * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}.
         */
        @Test
        void theTotalTravelsAsTextAtNineIntegerDigits() {
            Map<String, Object> total = property(envelopeSchema(), "totalAmount", "the envelope");
            assertEquals("string", textAt(total, TYPE, "totalAmount"), "the type of totalAmount");
            assertEquals(MONEY_PATTERN, textAt(total, PATTERN, "totalAmount"),
                    "the shape of totalAmount");
        }

        /** Asserts the array of entries names the schema the item record describes. */
        @Test
        void theArrayNamesTheItemSchema() {
            Map<String, Object> array = property(envelopeSchema(), "transactions", "the envelope");
            assertEquals("array", textAt(array, TYPE, "transactions"),
                    "the type of transactions");
            String reference =
                    textAt(mapAt(array, "items", "the array"), REFERENCE, "the array items");
            assertTrue(reference.startsWith(LOCAL_REFERENCE_PREFIX),
                    "the array items name a schema of this document");
            assertEquals(Set.copyOf(componentNamesOf(NotificationTransactionItem.class)),
                    propertiesOf(resolve(reference), "the item").keySet(),
                    "the schema the array items name against the item record components");
        }

        /** Asserts the envelope declares no offset paging property. */
        @Test
        void theEnvelopeDeclaresNoOffsetPagingProperty() {
            Set<String> declared = propertiesOf(envelopeSchema(), "the envelope").keySet();
            for (String absent : OFFSET_PAGING_PROPERTIES) {
                assertFalse(declared.contains(absent), "the envelope declares " + absent);
            }
        }
    }

    @Nested
    @DisplayName("The item: one property per field of TRNX-RECORD, every one text")
    class TheItemSchema {

        /**
         * Asserts the item declares exactly the components {@link NotificationTransactionItem}
         * carries, read by reflection, so a drift on either side fails. Each maps one field of
         * {@code 01 TRNX-RECORD.} at {@code app/cpy/COSTM01.CPY:L20}, from
         * {@code app/cpy/COSTM01.CPY:L23} through {@code app/cpy/COSTM01.CPY:L35}. The trailing
         * {@code FILLER PIC X(20)} at {@code app/cpy/COSTM01.CPY:L36} reaches no property.
         */
        @Test
        void theItemPropertiesAreTheRecordComponents() {
            List<String> components = componentNamesOf(NotificationTransactionItem.class);
            assertEquals(Set.copyOf(components), propertiesOf(itemSchema(), "the item").keySet(),
                    "the properties the item declares against the record components");
            assertEquals(List.of("transactionId", "typeCode", "categoryCode",
                    "source", "description", "amount", "merchantId", "merchantName",
                    "merchantCity", "merchantZip", "originTimestamp", "processingTimestamp"),
                    components, "the components of the item record");
        }

        /** Asserts the item declares its properties in the order the record declares them. */
        @Test
        void theItemDeclaresItsPropertiesInRecordOrder() {
            assertEquals(componentNamesOf(NotificationTransactionItem.class), itemPropertyOrder(),
                    "the order the item declares against the order the record declares");
        }

        /**
         * Asserts the item names no card at all. {@code TRNX-CARD-NUM PIC X(16)} at
         * {@code app/cpy/COSTM01.CPY:L22} sits inside {@code 05 TRNX-KEY} and reaches the envelope.
         * The source loop moves that field once per card group at
         * {@code app/cbl/CBSTM03A.CBL:L421} while setting the identifier and the remaining fields
         * once per transaction at {@code app/cbl/CBSTM03A.CBL:L424-L427}, so the card is named once
         * per history and not once per entry.
         */
        @Test
        void theItemNamesNoCard() {
            List<String> declared = itemPropertyOrder();
            for (String identity
                    : List.of("cardToken", "cardNumber", "cardNum", "maskedCardNumber", "pan")) {
                assertFalse(declared.contains(identity), "the item declares " + identity);
            }
            assertEquals(List.of(), declared.stream()
                            .filter(name -> name.toLowerCase(Locale.ROOT).contains("card"))
                            .toList(),
                    "the card-bearing properties of the item");
        }

        /** Asserts every property of the item is text, so no digit field travels as a number. */
        @Test
        void everyItemPropertyIsText() {
            for (String name : itemPropertyOrder()) {
                assertEquals("string", textAt(property(itemSchema(), name, "the item"), TYPE, name),
                        "the type of " + name);
            }
        }

        /**
         * Asserts the character bound of each free-text property, at the width its field declares.
         * <ul>
         *   <li>{@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23}</li>
         *   <li>{@code TRNX-TYPE-CD PIC X(02)} at {@code app/cpy/COSTM01.CPY:L25}</li>
         *   <li>{@code TRNX-SOURCE PIC X(10)} at {@code app/cpy/COSTM01.CPY:L27}</li>
         *   <li>{@code TRNX-MERCHANT-NAME PIC X(50)} at {@code app/cpy/COSTM01.CPY:L31}</li>
         *   <li>{@code TRNX-MERCHANT-CITY PIC X(50)} at {@code app/cpy/COSTM01.CPY:L32}</li>
         *   <li>{@code TRNX-MERCHANT-ZIP PIC X(10)} at {@code app/cpy/COSTM01.CPY:L33}</li>
         * </ul>
         */
        @Test
        void eachFreeTextPropertyCarriesItsFieldWidth() {
            assertEquals(16, itemBoundOf("transactionId"), "the width of transactionId");
            assertEquals(2, itemBoundOf("typeCode"), "the width of typeCode");
            assertEquals(10, itemBoundOf("source"), "the width of source");
            assertEquals(50, itemBoundOf("merchantName"), "the width of merchantName");
            assertEquals(50, itemBoundOf("merchantCity"), "the width of merchantCity");
            assertEquals(10, itemBoundOf("merchantZip"), "the width of merchantZip");
        }

        /**
         * Asserts the two digit-shaped properties travel as text at their declared digit counts:
         * {@code TRNX-CAT-CD PIC 9(04)} at {@code app/cpy/COSTM01.CPY:L26} and
         * {@code TRNX-MERCHANT-ID PIC 9(09)} at {@code app/cpy/COSTM01.CPY:L30}.
         */
        @Test
        void eachDigitPropertyCarriesItsDigitShape() {
            assertEquals(CATEGORY_CODE_PATTERN, itemShapeOf("categoryCode"),
                    "the shape of categoryCode");
            assertEquals(MERCHANT_ID_PATTERN, itemShapeOf("merchantId"),
                    "the shape of merchantId");
        }

        /**
         * Asserts the transaction identifier is bounded by width at both ends and carries no digit
         * shape, so the document describes no sixteen-digit value. From
         * {@code TRNX-ID PIC X(16)} at {@code app/cpy/COSTM01.CPY:L23}.
         */
        @Test
        void theTransactionIdentifierIsBoundedByWidthAlone() {
            Map<String, Object> identifier = property(itemSchema(), "transactionId", "the item");
            assertEquals(16, intAt(identifier, MIN_LENGTH, "transactionId"),
                    "the lower bound of transactionId");
            assertEquals(16, intAt(identifier, MAX_LENGTH, "transactionId"),
                    "the upper bound of transactionId");
            assertFalse(identifier.containsKey(PATTERN), "transactionId declares a shape");
        }
    }

    @Nested
    @DisplayName("The description carries the field width, and never the rendered width")
    class TheDescriptionWidth {

        /**
         * Asserts the description is bounded at {@value #DESCRIPTION_WIDTH} characters, the width
         * {@code TRNX-DESC PIC X(100)} declares at {@code app/cpy/COSTM01.CPY:L28}. The persisted
         * column {@code description CHAR(100)} of
         * {@code src/main/resources/db/migration/V1__schema.sql} carries the same width.
         */
        @Test
        void theDescriptionIsBoundedAtTheFieldWidth() {
            assertEquals(DESCRIPTION_WIDTH, itemBoundOf("description"),
                    "the width of description");
        }

        /**
         * Asserts the description is not bounded at {@value #RENDERED_DESCRIPTION_WIDTH}
         * characters. The move at {@code app/cbl/CBSTM03A.CBL:L677} truncates the field into the
         * {@value #RENDERED_DESCRIPTION_WIDTH}-character layout field declared at
         * {@code app/cbl/CBSTM03A.CBL:L135}, and that truncation belongs to the renderer of the
         * sibling {@code domain} package.
         */
        @Test
        void theDescriptionIsNotBoundedAtTheRenderedWidth() {
            assertNotEquals(RENDERED_DESCRIPTION_WIDTH, itemBoundOf("description"),
                    "the width of description");
        }
    }

    @Nested
    @DisplayName("The two timestamps carry different shapes, and neither names a calendar format")
    class TheTwoTimestamps {

        /**
         * Asserts the origin timestamp shape: a space between the day and the hour, colons between
         * the time parts, and six fractional digits. From {@code TRNX-ORIG-TS PIC X(26)} at
         * {@code app/cpy/COSTM01.CPY:L34}.
         */
        @Test
        void theOriginTimestampCarriesASpaceColonsAndSixFractionalDigits() {
            assertEquals(ORIGIN_TIMESTAMP_PATTERN, itemShapeOf("originTimestamp"),
                    "the shape of originTimestamp");
        }

        /**
         * Asserts the processing timestamp shape: a dash between the day and the hour, dots between
         * the time parts, two hundredths digits, then four zero characters. From
         * {@code TRNX-PROC-TS PIC X(26)} at {@code app/cpy/COSTM01.CPY:L35}, filled at
         * {@code app/cbl/CBTRN02C.cbl:L701}.
         */
        @Test
        void theProcessingTimestampCarriesADashDotsAndFourZeroCharacters() {
            assertEquals(PROCESSING_TIMESTAMP_PATTERN, itemShapeOf("processingTimestamp"),
                    "the shape of processingTimestamp");
        }

        /** Asserts the two timestamp shapes differ. */
        @Test
        void theTwoTimestampShapesDiffer() {
            assertNotEquals(itemShapeOf("originTimestamp"), itemShapeOf("processingTimestamp"),
                    "the shapes the two timestamp properties carry");
        }

        /**
         * Asserts both timestamps are bounded at {@value #TIMESTAMP_WIDTH} characters at both ends.
         * The copy step {@code OUTREC FIELDS=(1:263,16,17:1,262,279:279,50)} at
         * {@code app/jcl/CREASTMT.JCL:L54} fills target bytes 279 through 328, so the batch read
         * model receives twenty-four of the twenty-six processing characters. A posted transaction
         * fills the read model here, and the property carries all twenty-six.
         */
        @Test
        void bothTimestampsCarryAllTwentySixCharacters() {
            for (String name : List.of("originTimestamp", "processingTimestamp")) {
                Map<String, Object> stamp = property(itemSchema(), name, "the item");
                assertEquals(TIMESTAMP_WIDTH, intAt(stamp, MIN_LENGTH, name),
                        "the lower bound of " + name);
                assertEquals(TIMESTAMP_WIDTH, intAt(stamp, MAX_LENGTH, name),
                        "the upper bound of " + name);
            }
        }

        /**
         * Asserts each declared timestamp shape admits the example the document declares beside it,
         * so the two halves of each property agree.
         */
        @Test
        void eachDeclaredShapeAdmitsItsOwnExample() {
            for (String name : List.of("originTimestamp", "processingTimestamp")) {
                Map<String, Object> stamp = property(itemSchema(), name, "the item");
                List<Object> examples = listAt(stamp, "examples", name);
                assertFalse(examples.isEmpty(), name + " declares an example");
                Pattern shape = Pattern.compile(textAt(stamp, PATTERN, name));
                for (Object example : examples) {
                    assertTrue(shape.matcher(String.valueOf(example)).matches(),
                            "the shape of " + name + " admits its own example " + example);
                }
            }
        }

        /**
         * Asserts no property of the document names a calendar format, so neither timestamp is
         * described as a calendar value. The comparison at {@code app/cbl/CBTRN02C.cbl:L414-L420}
         * reads the source value as text.
         */
        @Test
        void noPropertyNamesACalendarFormat() {
            assertEquals(0, countOf(document, "date-time"),
                    "the text of the document names a calendar format");
            assertEquals(List.of(), everyValueOf("format"),
                    "the document declares a format somewhere");
            for (String name : List.of("originTimestamp", "processingTimestamp")) {
                assertFalse(property(itemSchema(), name, "the item").containsKey("format"),
                        name + " declares a format");
            }
        }
    }

    @Nested
    @DisplayName("Every monetary property is signed text of nine integer digits")
    class MonetaryShapes {

        /**
         * Reads every monetary property the document declares, keyed by the path it sits at.
         *
         * @return the monetary properties
         */
        private Map<String, Map<String, Object>> monetary() {
            Map<String, Map<String, Object>> found = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> declared
                    : everyDeclaredProperty().entrySet()) {
                String path = declared.getKey();
                String name = path.substring(path.lastIndexOf('/') + 1);
                if ("amount".equals(name) || "totalAmount".equals(name)) {
                    found.put(path, declared.getValue());
                }
            }
            return found;
        }

        /** Asserts the document declares the entry amount and the total, and no third money value. */
        @Test
        void theDocumentDeclaresTheAmountAndTheTotal() {
            Map<String, Map<String, Object>> money = monetary();
            assertEquals(2, money.size(),
                    "the monetary properties the document declares: " + money.keySet());
        }

        /**
         * Asserts every monetary property travels as text at the monetary shape. A JSON
         * (JavaScript Object Notation) number reaches most parsers as a binary floating point
         * value, and AAP section 0.3.1 holds every amount of this platform to fixed-point text.
         */
        @Test
        void everyMonetaryPropertyIsTextAtTheMonetaryShape() {
            for (Map.Entry<String, Map<String, Object>> money : monetary().entrySet()) {
                assertEquals("string", textAt(money.getValue(), TYPE, money.getKey()),
                        "the type of " + money.getKey());
                assertEquals(MONEY_PATTERN, textAt(money.getValue(), PATTERN, money.getKey()),
                        "the shape of " + money.getKey());
            }
        }

        /** Asserts no monetary property is a number of either kind. */
        @Test
        void noMonetaryPropertyIsANumber() {
            for (Map.Entry<String, Map<String, Object>> money : monetary().entrySet()) {
                assertNotEquals("number", money.getValue().get(TYPE),
                        "the type of " + money.getKey());
                assertNotEquals("integer", money.getValue().get(TYPE),
                        "the type of " + money.getKey());
            }
            assertFalse(document.contains("type: number"),
                    "the document declares a value of type number");
        }

        /**
         * Asserts the monetary shape admits {@value #MONEY_INTEGER_DIGITS} integer digits, refuses
         * one more, and admits a leading minus. The ceiling comes from
         * {@code TRNX-AMT PIC S9(09)V99} at {@code app/cpy/COSTM01.CPY:L29} and from
         * {@code WS-TOTAL-AMT PIC S9(9)V99} at {@code app/cbl/CBSTM03A.CBL:L65}.
         */
        @Test
        void theMonetaryShapeAdmitsNineIntegerDigitsAndRefusesTen() {
            Pattern money = Pattern.compile(MONEY_PATTERN);
            String ceiling = "1".repeat(MONEY_INTEGER_DIGITS) + ".00";
            String beyond = "1".repeat(MONEY_INTEGER_DIGITS + 1) + ".00";
            assertTrue(money.matcher(ceiling).matches(),
                    "the monetary shape admits " + MONEY_INTEGER_DIGITS + " integer digits");
            assertFalse(money.matcher(beyond).matches(),
                    "the monetary shape admits " + (MONEY_INTEGER_DIGITS + 1) + " integer digits");
            assertTrue(money.matcher("-" + ceiling).matches(),
                    "the monetary shape admits a refund carrying a leading minus");
        }
    }

    @Nested
    @DisplayName("What the document declares nowhere")
    class DocumentWideAbsences {

        /** Asserts the document declares no callback and no webhook. */
        @Test
        void noCallbackAndNoWebhookIsDeclared() {
            assertFalse(everyKey().contains("callbacks"), "the document declares a callback");
            assertFalse(model.containsKey("webhooks"), "the document declares a webhook");
        }

        /** Asserts the document carries no vendor extension. */
        @Test
        void noVendorExtensionIsDeclared() {
            assertEquals(List.of(),
                    everyKey().stream().filter(key -> key.startsWith("x-")).toList(),
                    "the document carries a vendor extension");
        }

        /** Asserts every reference stays inside the document. */
        @Test
        void everyReferenceStaysInsideTheDocument() {
            List<Object> references = everyValueOf(REFERENCE);
            assertFalse(references.isEmpty(), "the document carries a reference");
            for (Object reference : references) {
                assertTrue(String.valueOf(reference).startsWith(LOCAL_REFERENCE_PREFIX),
                        "the reference " + reference + " leaves the document");
            }
        }

        /**
         * Asserts the document names no field the account service owns. The names and their
         * locators inside {@code app/cbl/CBSTM03A.CBL} sit on
         * {@link NotificationOpenApiContractTest#ACCOUNT_OWNED_PROPERTIES}.
         */
        @Test
        void noFieldTheAccountServiceOwnsAppears() {
            String lowered = document.toLowerCase(Locale.ROOT);
            for (String owned : ACCOUNT_OWNED_PROPERTIES) {
                assertFalse(lowered.contains(owned.toLowerCase(Locale.ROOT)),
                        "the document names " + owned + ", which the account service owns");
            }
        }

        /**
         * Asserts the document names the card verification value nowhere, in code or in full. The
         * card record declares that field at {@code app/cpy/CVACT02Y.cpy:L7}, and no response, log
         * or event of this platform carries it.
         */
        @Test
        void theCardVerificationValueIsNamedNowhere() {
            String lowered = document.toLowerCase(Locale.ROOT);
            for (String absent : CARD_VERIFICATION_PROPERTIES) {
                assertFalse(lowered.contains(absent.toLowerCase(Locale.ROOT)),
                        "the document names the card verification value");
            }
        }

         /**
         * Asserts the document declares no decline outcome of the authorization path, and no
         * enumeration at all: every fixed header value it declares is a {@code const}.
         */
        @Test
        void noDeclineOutcomeOfTheAuthorizationPathAppears() {
            Set<String> declared = everyPropertyName();
            for (String absent : DECLINE_PROPERTIES) {
                assertFalse(declared.contains(absent),
                        "the document declares the property " + absent);
            }
            List<Object> enumerations = everyValueOf("enum");
            assertEquals(List.of(), enumerations,
                    "the document declares an enumeration where a fixed value belongs");
        }

        /**
         * Asserts the successful response documents the caching header Spring Security writes,
         * character for character.
         *
         * <p>{@code config/SecurityConfig} installs {@code CacheControlHeadersWriter} through
         * {@code headers().cacheControl()}, and that writer sets four directives rather than
         * {@code no-store} alone. A document naming one directive would advertise bytes no response
         * carries.
         */
        @Test
        void theSuccessfulResponseDocumentsTheCachingHeaderTheChainWrites() {
            Map<String, Object> success = mapAt(responses(), "200", "the successful response");
            Map<String, Object> headers = mapAt(success, "headers", "the successful response");
            Map<String, Object> caching = mapAt(headers, "Cache-Control", "the caching header");
            Map<String, Object> schema = mapAt(caching, "schema", "the caching header");

            assertEquals(Set.of("Cache-Control"), headers.keySet(),
                    "the headers the successful response documents");
            assertEquals(Boolean.TRUE, caching.get("required"), "the header is always present");
            assertEquals("no-cache, no-store, max-age=0, must-revalidate",
                    textAt(schema, "const", "the caching header schema"),
                    "the directives the response carries");
        }

        /** Asserts no response restates an event envelope. */
        @Test
        void noResponseRestatesAnEventEnvelope() {
            Set<String> declared = everyPropertyName();
            for (String absent : EVENT_ENVELOPE_PROPERTIES) {
                assertFalse(declared.contains(absent),
                        "the document declares the property " + absent);
            }
        }

        /**
         * Asserts no property names a delivery channel, and none names the status of a card or of
         * an account.
         */
        @Test
        void noDeliveryChannelAndNoCardOrAccountStatusAppears() {
            Set<String> declared = everyPropertyName();
            for (String absent : DELIVERY_CHANNEL_PROPERTIES) {
                assertFalse(declared.contains(absent),
                        "the document declares the property " + absent);
            }
            for (String absent : STATUS_PROPERTIES) {
                assertFalse(declared.contains(absent),
                        "the document declares the property " + absent);
            }
        }

        /**
         * Asserts the document carries no run of sixteen digits at all, and that the one value it
         * publishes for the path is the all-zero placeholder token.
         *
         * <p>The route reads a card token rather than a card number, so nothing the document
         * publishes needs the shape of a card number and no example carries one. A response names a
         * card by its masked form, which is twelve mask characters and four digits. The message
         * counts the runs and prints none of them.
         *
         * <p>The published token is sixty-four zeros. A real token is the keyed code over a number
         * under a deployment-supplied key, so no token this document could publish resolves under a
         * reader's key, and one derived under the build key would name a seeded card to anyone
         * holding that key.
         */
        @Test
        void theDocumentCarriesNoSixteenDigitRunAndPublishesThePlaceholderToken() {
            Matcher run = SIXTEEN_DIGIT_RUN.matcher(document);
            int runs = 0;
            while (run.find()) {
                runs++;
            }
            assertEquals(0, runs, "the document carries " + runs + " runs of sixteen digits");

            Map<String, Object> examples = mapAt(pathParameter(), "examples", "the path parameter");
            Map<String, Object> example =
                    mapAt(examples, "token", "the path parameter examples");
            assertEquals(PanMasker.ABSENT_CARD_TOKEN,
                    textAt(example, "value", "the path parameter example"),
                    "the example the path parameter publishes");
        }

        /**
         * Asserts every property name of the document reads as lower camel case, so no layout
         * identifier of the source reaches the interface. A Basic Mapping Support field name and a
         * statement line name both carry characters the shape refuses.
         */
        @Test
        void everyPropertyNameReadsAsLowerCamelCase() {
            for (String name : everyPropertyName()) {
                assertTrue(LOWER_CAMEL_CASE.matcher(name).matches(),
                        "the property name " + name + " reads as lower camel case");
            }
        }
    }

    /**
     * Names the initials of a phrase, lower-cased.
     *
     * @param phrase the phrase to read
     * @return the upper-case letters of {@code phrase}, lower-cased and joined
     */
    private static String initialsOf(String phrase) {
        StringBuilder initials = new StringBuilder();
        for (int index = 0; index < phrase.length(); index++) {
            char letter = phrase.charAt(index);
            if (Character.isUpperCase(letter)) {
                initials.append(Character.toLowerCase(letter));
            }
        }
        return initials.toString();
    }

    /**
     * Reads a parsed node as a map keyed by text, preserving the order the document declares.
     *
     * @param node the parsed node
     * @param what names the node in a failure message
     * @return the node as a map keyed by text
     */
    private static Map<String, Object> stringKeyed(Object node, String what) {
        assertTrue(node instanceof Map<?, ?>, what + " is a mapping");
        Map<String, Object> keyed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) node).entrySet()) {
            keyed.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return keyed;
    }

    /**
     * Reads a nested mapping.
     *
     * @param parent the mapping to read from
     * @param key the key to read
     * @param what names the value in a failure message
     * @return the nested mapping
     */
    private static Map<String, Object> mapAt(Map<String, Object> parent, String key, String what) {
        Object value = parent.get(key);
        assertNotNull(value, what + " declares " + key);
        return stringKeyed(value, what + " " + key);
    }

    /**
     * Reads a nested sequence.
     *
     * @param parent the mapping to read from
     * @param key the key to read
     * @param what names the value in a failure message
     * @return the nested sequence
     */
    private static List<Object> listAt(Map<String, Object> parent, String key, String what) {
        Object value = parent.get(key);
        assertNotNull(value, what + " declares " + key);
        assertTrue(value instanceof List<?>, what + " " + key + " is a sequence");
        return new ArrayList<>((List<?>) value);
    }

    /**
     * Reads a text value.
     *
     * @param parent the mapping to read from
     * @param key the key to read
     * @param what names the value in a failure message
     * @return the text value
     */
    private static String textAt(Map<String, Object> parent, String key, String what) {
        Object value = parent.get(key);
        assertNotNull(value, what + " declares " + key);
        assertTrue(value instanceof String, what + " " + key + " is text");
        return (String) value;
    }

    /**
     * Reads a whole-number value.
     *
     * @param parent the mapping to read from
     * @param key the key to read
     * @param what names the value in a failure message
     * @return the value as a whole number
     */
    private static int intAt(Map<String, Object> parent, String key, String what) {
        Object value = parent.get(key);
        assertNotNull(value, what + " declares " + key);
        assertTrue(value instanceof Number, what + " " + key + " is a number");
        return ((Number) value).intValue();
    }

    /**
     * Reads the mapping of routes the document declares.
     *
     * @return the routes
     */
    private static Map<String, Object> paths() {
        return mapAt(model, "paths", "the document");
    }

    /**
     * Names the one route the document declares.
     *
     * @return the route key
     */
    private static String routeKey() {
        Map<String, Object> paths = paths();
        assertEquals(1, paths.size(), "the document declares one route, and declares " + paths);
        return paths.keySet().iterator().next();
    }

    /**
     * Reads the one operation the one route declares.
     *
     * @return the operation
     */
    private static Map<String, Object> operation() {
        Map<String, Object> pathItem = mapAt(paths(), routeKey(), "the route");
        assertEquals(1, pathItem.size(),
                "the route declares one operation, and declares " + pathItem.keySet());
        assertEquals("get", pathItem.keySet().iterator().next(), "the one operation reads");
        return mapAt(pathItem, "get", "the route");
    }

    /**
     * Reads every parameter the operation declares.
     *
     * @return the parameters
     */
    private static List<Map<String, Object>> parameters() {
        List<Map<String, Object>> declared = new ArrayList<>();
        int index = 0;
        for (Object entry : listAt(operation(), "parameters", "the operation")) {
            declared.add(stringKeyed(entry, "parameter " + index));
            index++;
        }
        return declared;
    }

    /**
     * Reads every response the operation declares.
     *
     * @return the responses, keyed by the status text the document declares
     */
    private static Map<String, Object> responses() {
        return mapAt(operation(), "responses", "the operation");
    }

    /**
     * Reads one schema of the components section.
     *
     * @param name the schema name
     * @return the schema
     */
    private static Map<String, Object> schema(String name) {
        Map<String, Object> components = mapAt(model, "components", "the document");
        return mapAt(mapAt(components, "schemas", "the components"), name, "the schema " + name);
    }

    /**
     * Resolves a reference of the components section to the schema it names.
     *
     * @param reference the reference value
     * @return the referenced schema
     */
    private static Map<String, Object> resolve(String reference) {
        assertTrue(reference.startsWith(LOCAL_REFERENCE_PREFIX),
                "the reference " + reference + " stays inside the document");
        return schema(reference.substring(reference.lastIndexOf('/') + 1));
    }

    /**
     * Reads the schema the successful response carries, following the reference the document
     * declares.
     *
     * @return the envelope schema
     */
    private static Map<String, Object> envelopeSchema() {
        return resolve(textAt(bodySchemaOf("200"), REFERENCE, "the successful body"));
    }

    /**
     * Reads the schema one entry of the history carries, following the reference the envelope
     * declares for its array.
     *
     * @return the item schema
     */
    private static Map<String, Object> itemSchema() {
        Map<String, Object> array =
                property(envelopeSchema(), "transactions", "the envelope");
        Map<String, Object> items = mapAt(array, "items", "the array");
        return resolve(textAt(items, REFERENCE, "the array items"));
    }

    /**
     * Reads the schema a failing response carries.
     *
     * <p>Two media types answer, and which one a status carries follows from where the answer is
     * written. {@code api/NotificationApiExceptionHandler} runs inside the dispatcher and
     * serializes {@link ApiErrorResponse} as {@code application/json}. A refusal written in the
     * filter chain reaches no handler and no serializer, so {@code config/SecurityConfig} and
     * {@code config/RequestRateCeilingFilter} write the problem document RFC 9457 defines as
     * {@code application/problem+json} instead. Publishing one type for both would describe a body
     * no code path produces.
     *
     * @param status the status key the document declares
     * @return the failing body schema
     */
    private static Map<String, Object> bodySchemaOf(String status) {
        Map<String, Object> response = mapAt(responses(), status, "the response " + status);
        Map<String, Object> content = mapAt(response, "content", "the response " + status);
        String mediaType = CHAIN_REFUSAL_STATUSES.contains(status)
                ? PROBLEM_MEDIA_TYPE : JSON_MEDIA_TYPE;
        assertEquals(List.of(mediaType), new ArrayList<>(content.keySet()),
                "the response " + status + " declares one media type");
        Map<String, Object> media = mapAt(content, mediaType, "the response " + status);
        return mapAt(media, "schema", "the response " + status);
    }

    /**
     * Reads the schema the failing responses carry.
     *
     * @return the error schema
     */
    private static Map<String, Object> errorSchema() {
        return resolve(textAt(bodySchemaOf("400"), REFERENCE, "the refusal body"));
    }

    /**
     * Reads the one parameter the operation carries in the path.
     *
     * @return the path parameter
     */
    private static Map<String, Object> pathParameter() {
        List<Map<String, Object>> inPath = parameters().stream()
                .filter(parameter -> "path".equals(parameter.get("in")))
                .toList();
        assertEquals(1, inPath.size(), "the operation carries one parameter in the path, and carries "
                + namesOf(parameters()));
        return inPath.get(0);
    }

    /**
     * Names the parameters of a list, in the order the document declares them.
     *
     * @param declared the parameters to name
     * @return the parameter names
     */
    private static List<String> namesOf(List<Map<String, Object>> declared) {
        return declared.stream().map(parameter -> String.valueOf(parameter.get("name"))).toList();
    }

    /**
     * Names the statuses the operation documents, normalised to text so a numeric key and a quoted
     * key compare equal.
     *
     * @return the status keys
     */
    private static List<String> statusKeys() {
        return responses().keySet().stream().map(String::valueOf).toList();
    }

    /**
     * Names the properties of the item schema, in the order the document declares them.
     *
     * @return the property names
     */
    private static List<String> itemPropertyOrder() {
        return new ArrayList<>(propertiesOf(itemSchema(), "the item").keySet());
    }

    /**
     * Reads the upper character bound one property of the item schema declares.
     *
     * @param name the property name
     * @return the upper character bound
     */
    private static int itemBoundOf(String name) {
        return intAt(property(itemSchema(), name, "the item"), MAX_LENGTH, name);
    }

    /**
     * Reads the shape one property of the item schema declares.
     *
     * @param name the property name
     * @return the declared shape
     */
    private static String itemShapeOf(String name) {
        return textAt(property(itemSchema(), name, "the item"), PATTERN, name);
    }

    /**
     * Reads the declared properties of a schema.
     *
     * @param schema the schema to read
     * @param what names the schema in a failure message
     * @return the properties, in the order the document declares them
     */
    private static Map<String, Object> propertiesOf(Map<String, Object> schema, String what) {
        return mapAt(schema, PROPERTIES, what);
    }

    /**
     * Reads one declared property of a schema.
     *
     * @param schema the schema to read
     * @param name the property name
     * @param what names the schema in a failure message
     * @return the property schema
     */
    private static Map<String, Object> property(Map<String, Object> schema, String name,
            String what) {
        return mapAt(propertiesOf(schema, what), name, what + " property " + name);
    }

    /**
     * Finds every schema object of the parsed document, keyed by the path it sits at.
     *
     * <p>A schema object is a mapping carrying {@value #PROPERTIES}, or a mapping declaring
     * {@value #TYPE} {@value #OBJECT_TYPE}.
     *
     * @return the schema objects, keyed by path
     */
    private static Map<String, Map<String, Object>> schemaObjects() {
        Map<String, Map<String, Object>> found = new LinkedHashMap<>();
        collectSchemaObjects("#", model, found);
        return found;
    }

    /**
     * Walks one node of the parsed document and collects the schema objects at or below it.
     *
     * @param path the path of {@code node}
     * @param node the node to walk
     * @param found the schema objects found so far
     */
    private static void collectSchemaObjects(String path, Object node,
            Map<String, Map<String, Object>> found) {
        if (node instanceof Map<?, ?> mapping) {
            Map<String, Object> keyed = stringKeyed(mapping, path);
            if (keyed.containsKey(PROPERTIES) || OBJECT_TYPE.equals(keyed.get(TYPE))) {
                found.put(path, keyed);
            }
            for (Map.Entry<String, Object> entry : keyed.entrySet()) {
                collectSchemaObjects(path + "/" + entry.getKey(), entry.getValue(), found);
            }
        } else if (node instanceof List<?> sequence) {
            for (int index = 0; index < sequence.size(); index++) {
                collectSchemaObjects(path + "/" + index, sequence.get(index), found);
            }
        }
    }

    /**
     * Names every key of the parsed document, at every depth.
     *
     * @return the keys
     */
    private static List<String> everyKey() {
        List<String> keys = new ArrayList<>();
        collectKeys(model, keys);
        return keys;
    }

    /**
     * Walks one node and collects the keys at or below it.
     *
     * @param node the node to walk
     * @param keys the keys found so far
     */
    private static void collectKeys(Object node, List<String> keys) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                keys.add(String.valueOf(entry.getKey()));
                collectKeys(entry.getValue(), keys);
            }
        } else if (node instanceof List<?> sequence) {
            for (Object entry : sequence) {
                collectKeys(entry, keys);
            }
        }
    }

    /**
     * Reads every value the parsed document declares under one key, at every depth.
     *
     * @param key the key to read
     * @return the values
     */
    private static List<Object> everyValueOf(String key) {
        List<Object> values = new ArrayList<>();
        collectValuesOf(key, model, values);
        return values;
    }

    /**
     * Walks one node and collects the values declared under one key at or below it.
     *
     * @param key the key to read
     * @param node the node to walk
     * @param values the values found so far
     */
    private static void collectValuesOf(String key, Object node, List<Object> values) {
        if (node instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if (key.equals(String.valueOf(entry.getKey()))) {
                    values.add(entry.getValue());
                }
                collectValuesOf(key, entry.getValue(), values);
            }
        } else if (node instanceof List<?> sequence) {
            for (Object entry : sequence) {
                collectValuesOf(key, entry, values);
            }
        }
    }

    /**
     * Names every property every schema object of the document declares.
     *
     * @return the property names
     */
    private static Set<String> everyPropertyName() {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> schema : schemaObjects().values()) {
            Object properties = schema.get(PROPERTIES);
            if (properties instanceof Map<?, ?> mapping) {
                names.addAll(stringKeyed(mapping, "a properties mapping").keySet());
            }
        }
        return names;
    }

    /**
     * Reads every declared property of the document, keyed by the schema path and the name.
     *
     * @return the properties
     */
    private static Map<String, Map<String, Object>> everyDeclaredProperty() {
        Map<String, Map<String, Object>> declared = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> schema : schemaObjects().entrySet()) {
            Object properties = schema.getValue().get(PROPERTIES);
            if (properties instanceof Map<?, ?> mapping) {
                Map<String, Object> keyed = stringKeyed(mapping, "a properties mapping");
                for (Map.Entry<String, Object> entry : keyed.entrySet()) {
                    declared.put(schema.getKey() + "/" + entry.getKey(),
                            stringKeyed(entry.getValue(), "the property " + entry.getKey()));
                }
            }
        }
        return declared;
    }

    /**
     * Names the record components of one record, in declaration order.
     *
     * @param record the record class
     * @return the component names
     */
    private static List<String> componentNamesOf(Class<?> record) {
        RecordComponent[] components = record.getRecordComponents();
        assertNotNull(components, record.getSimpleName() + " is a record");
        return Arrays.stream(components).map(RecordComponent::getName).toList();
    }

    /**
     * Counts how many times one text appears in another.
     *
     * @param text the text to search
     * @param sought the text to count
     * @return the count
     */
    private static int countOf(String text, String sought) {
        int count = 0;
        int at = text.indexOf(sought);
        while (at >= 0) {
            count++;
            at = text.indexOf(sought, at + sought.length());
        }
        return count;
    }
}
