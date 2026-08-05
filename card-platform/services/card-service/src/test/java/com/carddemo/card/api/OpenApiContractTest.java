package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardDetailRequest;
import com.carddemo.card.api.dto.CardDetailResponse;
import com.carddemo.card.api.dto.CardListResponse;
import com.carddemo.card.api.dto.CardSummary;
import com.carddemo.card.api.dto.CardUpdateRequest;
import com.carddemo.card.api.dto.CardUpdateResponse;
import com.carddemo.card.api.dto.CardUpdateResponse.RefreshedCard;
import com.carddemo.card.api.dto.CardUpdateResponse.UpdateOutcome;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.cobol.PanMasker;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Agreement tests between {@code src/main/resources/openapi.yaml} and the code it describes.
 *
 * <p>The description is written by hand, because no documentation generator joins the classpath.
 * That choice buys a document a reader can follow and costs a way for the two to drift, so these
 * tests are the mechanism that stops the drift. A component added to a record without a property in
 * the document fails here, and so does the reverse. A route the document describes that
 * {@link CardController} does not map fails here too.
 *
 * <p>These tests read one classpath resource and use reflection. No application context, no database
 * and no broker takes part.
 */
@DisplayName("the hand-written card service description")
final class OpenApiContractTest {

    /** The hand-written description of this service. */
    private static final String DOCUMENT = "openapi.yaml";

    /** The collection route, which carries the list and the update. */
    private static final String COLLECTION_PATH = "/cards";

    /** The read route, which carries the search condition in its body. */
    private static final String DETAIL_PATH = "/cards/detail";

    /** The parsed document every test below reads. */
    private static Map<String, Object> openApi;

    /** Reads the document from the classpath once. */
    @BeforeAll
    @SuppressWarnings("unchecked")
    static void readDocument() throws Exception {
        try (InputStream source =
                OpenApiContractTest.class.getClassLoader().getResourceAsStream(DOCUMENT)) {
            assertTrue(source != null, DOCUMENT + " ships on the classpath of this service");
            openApi = (Map<String, Object>) new Yaml().load(source);
        }
    }

    /** The routes and the methods the document describes. */
    @Nested
    @DisplayName("routes and methods")
    class RoutesAndMethods {

        /**
         * Asserts the document describes the two routes this service serves, and no other.
         *
         * <p>{@code config/SecurityConfig} names exactly these two paths across three rules and denies
         * everything else, so a route in the document the chain does not name would be a route a
         * caller cannot reach.
         */
        @Test
        void theDocumentDescribesTheTwoRoutes() {
            assertEquals(Set.of(COLLECTION_PATH, DETAIL_PATH), paths().keySet(),
                    "three Customer Information Control System transactions over two paths");
        }

        /**
         * Asserts every route the document describes is a route the controller maps, and the reverse.
         *
         * <p>This is the assertion that makes the document a contract rather than a description. A
         * mapping with no operation would be an undocumented route, and an operation with no mapping
         * would be a documented 404.
         */
        @Test
        void theDocumentedOperationsAreTheMappedOnes() {
            Set<String> documented = new TreeSet<>();
            paths().forEach((path, operations) -> operations.keySet()
                    .forEach(method -> documented.add(method.toUpperCase(Locale.ROOT) + " " + path)));

            assertEquals(new TreeSet<>(List.of("GET " + COLLECTION_PATH, "PUT " + COLLECTION_PATH,
                            "POST " + DETAIL_PATH)),
                    documented,
                    "app/cbl/COCRDLIC.cbl lists, app/cbl/COCRDSLC.cbl reads and"
                            + " app/cbl/COCRDUPC.cbl updates");
        }

        /**
         * Asserts each operation identifier names the handler method that serves it.
         *
         * <p>An identifier is what a client generator turns into a method name, so a stale one
         * survives every other check in this class and misleads every reader of a generated client.
         */
        @Test
        void eachOperationIdentifierNamesItsHandler() {
            Set<String> handlers = Arrays.stream(CardController.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName)
                    .collect(Collectors.toCollection(TreeSet::new));

            for (Map.Entry<String, Map<String, Object>> route : paths().entrySet()) {
                for (Map.Entry<String, Object> operation : route.getValue().entrySet()) {
                    String identifier =
                            String.valueOf(asMap(operation.getValue()).get("operationId"));
                    assertTrue(handlers.contains(identifier),
                            operation.getKey() + " " + route.getKey() + " names operation "
                                    + identifier + ", which no method of CardController declares");
                }
            }
        }

        /**
         * Asserts every route requires a credential.
         *
         * <p>The requirement is declared once at the top level, so a route added without its own
         * security block inherits it rather than opening.
         */
        @Test
        void everyRouteRequiresACredential() {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> security =
                    (List<Map<String, Object>>) openApi.get("security");
            assertEquals(List.of("basicIdentity"), new ArrayList<>(security.get(0).keySet()),
                    "config/SecurityConfig installs HTTP Basic authentication");
            assertEquals(1, security.size(), "one requirement, so no route is optional-credential");
            assertTrue(securitySchemes().containsKey("basicIdentity"),
                    "the scheme the requirement names is defined");
            for (Map<String, Object> operations : paths().values()) {
                for (Object operation : operations.values()) {
                    assertFalse(asMap(operation).containsKey("security"),
                            "no operation overrides the top-level requirement, so none can open it");
                }
            }
        }

        /**
         * Asserts no route carries a card number in its path and none declares one as a query
         * parameter.
         *
         * <p>A path and a query string reach an access log, a reverse proxy log and a browser history.
         * The two routes that name one card take it in the body, and the paging cursor travels in a
         * header for the same reason.
         */
        @Test
        void noRouteCarriesACardNumberInItsPathOrQuery() {
            for (String path : paths().keySet()) {
                assertFalse(path.contains("{"), path + " declares a path variable, and the only"
                        + " identifiers this service keys on are a card number and an account");
            }
            for (Map<String, Object> operations : paths().values()) {
                for (Object operation : operations.values()) {
                    for (Object parameter : parametersOf(operation)) {
                        Map<String, Object> declared = asMap(parameter);
                        String name = String.valueOf(declared.get("name"));
                        if ("query".equals(declared.get("in"))) {
                            assertFalse(name.toLowerCase(Locale.ROOT).contains("card"),
                                    name + " is a query parameter naming a card");
                        }
                    }
                }
            }
        }
    }

    /** The request values the list route accepts, against the constants the controller declares. */
    @Nested
    @DisplayName("list parameters against the controller")
    class ListParametersAgainstTheController {

        /** Asserts the four parameter names are the ones the controller binds. */
        @Test
        void theFourParameterNamesAreTheOnesTheControllerBinds() {
            assertEquals(List.of(CardController.ACCOUNT_ID_PARAMETER, CardController.CURSOR_HEADER,
                            CardController.DIRECTION_PARAMETER, "pageSize"),
                    parameterNamesOf(COLLECTION_PATH, "get"),
                    "each name is part of the wire contract, and the account name is part of the"
                            + " access-control contract as well");
        }

        /**
         * Asserts the account parameter is required and carries the pattern the controller enforces.
         *
         * <p>The rule in {@code config/SecurityConfig} reads this parameter to decide ownership, so a
         * document calling it optional would describe a route that denies every request.
         */
        @Test
        void theAccountParameterIsRequiredAndCarriesTheControllerPattern() {
            Map<String, Object> account = parameterOf(COLLECTION_PATH, "get",
                    CardController.ACCOUNT_ID_PARAMETER);
            assertEquals(Boolean.TRUE, account.get("required"),
                    "the ownership rule reads it, so it cannot be omitted");
            assertEquals("query", account.get("in"), "an account identifier is not a card number");
            assertEquals(CardController.ACCOUNT_ID_PATTERN,
                    asMap(account.get("schema")).get("pattern"),
                    "CARD-ACCT-ID PIC 9(11) at app/cpy/CVACT02Y.cpy:L6");
        }

        /**
         * Asserts the cursor is a header, is optional, and carries the pattern the controller
         * enforces.
         */
        @Test
        void theCursorIsAnOptionalHeaderCarryingTheControllerPattern() {
            Map<String, Object> cursor =
                    parameterOf(COLLECTION_PATH, "get", CardController.CURSOR_HEADER);
            assertEquals("header", cursor.get("in"),
                    "the token remains outside the query string");
            assertEquals(Boolean.FALSE, cursor.get("required"),
                    "an absent cursor asks for the first page forward or the last page back");
            assertEquals(CardController.CURSOR_PATTERN, asMap(cursor.get("schema")).get("pattern"),
                    "the document accepts the same irreversible token as the controller");
        }

        /** Asserts the direction enumerates the two values the controller accepts and defaults to
         * forward. */
        @Test
        void theDirectionEnumeratesTheTwoValuesTheControllerAccepts() {
            Map<String, Object> schema =
                    asMap(parameterOf(COLLECTION_PATH, "get", CardController.DIRECTION_PARAMETER)
                            .get("schema"));
            assertEquals(List.of(CardController.FORWARD_DIRECTION,
                            CardController.BACKWARD_DIRECTION), schema.get("enum"),
                    "9000-READ-FORWARD and 9100-READ-BACKWARDS, and no third way");
            assertEquals(CardController.FORWARD_DIRECTION, schema.get("default"),
                    "the controller defaults the same way");
            for (Object value : (List<?>) schema.get("enum")) {
                assertTrue(String.valueOf(value).matches(CardController.DIRECTION_PATTERN),
                        value + " is enumerated but the controller pattern refuses it");
            }
        }

        /**
         * Asserts the page size describes the seven rows the source screen holds and the range the
         * read side accepts.
         *
         * <p>Seven is not an arbitrary default: {@code WS-MAX-SCREEN-LINES} at
         * {@code app/cbl/COCRDLIC.cbl:L177-L178} is the row count of the screen the source paints.
         */
        @Test
        void thePageSizeDescribesTheSevenRowsTheScreenHolds() {
            Map<String, Object> schema =
                    asMap(parameterOf(COLLECTION_PATH, "get", "pageSize").get("schema"));
            assertEquals("integer", schema.get("type"), "a row count is a whole number");
            assertEquals(7, schema.get("default"), "WS-MAX-SCREEN-LINES at COCRDLIC L177-L178");
            assertEquals(1, schema.get("minimum"), "a page of no row is not a page");
            assertEquals(100, schema.get("maximum"), "the read side refuses more");
        }
    }

    /** The status codes each operation answers with. */
    @Nested
    @DisplayName("status codes")
    class StatusCodes {

        /**
         * Asserts the list answers five codes and never 404.
         *
         * <p>A browse that reaches end of file is not an error:
         * {@code app/cbl/COCRDLIC.cbl:L1235} clears the next-page flag and the screen shows no row.
         */
        @Test
        void theListAnswersFiveCodesAndNeverNotFound() {
            assertEquals(Set.of("200", "400", "401", "403", "500"),
                    responsesOf(COLLECTION_PATH, "get").keySet(),
                    "an account with no card is an empty page and not an absent thing");
        }

        /**
         * Asserts the update answers exactly the codes its three writers can produce.
         *
         * <p>The set is derived rather than transcribed: five statuses come from
         * {@link CardController#statusOf(CardUpdateResponse)} over all seven outcomes, one from the
         * unreadable-body handler, and two from the filter chain. A new outcome mapped to a status the
         * document omits fails here.
         */
        @Test
        void theUpdateAnswersTheCodesItsThreeWritersProduce() {
            TreeSet<String> reachable = new TreeSet<>();
            for (CardUpdateResponse outcome : everyOutcome()) {
                reachable.add(String.valueOf(CardController.statusOf(outcome).value()));
            }
            reachable.add("400");
            reachable.add("401");
            reachable.add("403");

            assertEquals(reachable, new TreeSet<>(responsesOf(COLLECTION_PATH, "put").keySet()),
                    "five outcome statuses, one unreadable body, and two the chain writes");
        }

        /** Asserts the read answers seven codes. */
        @Test
        void theReadAnswersSevenCodes() {
            assertEquals(Set.of("200", "400", "401", "403", "404", "422", "500"),
                    responsesOf(DETAIL_PATH, "post").keySet(),
                    "a body to malform, four edits to fail, an ownership refusal and an absent row");
        }

        /**
         * Asserts every one of the seven outcomes reaches a status the document describes.
         *
         * <p>The other direction of the derivation above, stated per outcome so a failure names the
         * outcome rather than a set difference.
         */
        @Test
        void everyOutcomeReachesADocumentedStatus() {
            Set<String> documented = responsesOf(COLLECTION_PATH, "put").keySet();
            for (CardUpdateResponse outcome : everyOutcome()) {
                String status = String.valueOf(CardController.statusOf(outcome).value());
                assertTrue(documented.contains(status),
                        outcome.outcome() + " answers " + status + ", which the document omits");
            }
        }

        /**
         * Asserts the credential refusal answers a problem document on every operation.
         *
         * <p>{@code config/SecurityConfig} writes the 401 body by hand as
         * {@code application/problem+json}, before any handler runs, so all three operations answer
         * the same shape and a client parses one.
         */
        @Test
        void everyCredentialRefusalAnswersAProblemDocument() {
            for (Map.Entry<String, Map<String, Object>> route : paths().entrySet()) {
                for (String method : route.getValue().keySet()) {
                    Object unauthorized = responsesOf(route.getKey(), method).get("401");
                    assertEquals(Set.of("application/problem+json"), contentTypesOf(unauthorized),
                            method + " " + route.getKey() + " answers 401 in another shape");
                    assertEquals("Problem", schemaNameOf(unauthorized),
                            method + " " + route.getKey() + " answers 401 with another schema");
                }
            }
        }

        /**
         * Asserts a refusal answers the shape of whichever writer produced it.
         *
         * <p>The two routes the chain guards by rule answer a problem document, because the chain
         * writes the body before a handler runs. The read route decides ownership in the handler,
         * because the identifier sits in the body and a chain that read the body would consume the
         * stream the handler needs, so its 403 answers the same error record every other refusal of
         * that route answers. Describing that difference is the point: a client of the read route
         * parses one shape for all seven of its outcomes.
         */
        @Test
        void eachRefusalAnswersTheShapeOfItsWriter() {
            assertEquals("Problem", schemaNameOf(responsesOf(COLLECTION_PATH, "get").get("403")),
                    "the chain decides ownership of the list from the query parameter");
            assertEquals("Problem", schemaNameOf(responsesOf(COLLECTION_PATH, "put").get("403")),
                    "the chain decides the update by role");
            assertEquals("ApiError", schemaNameOf(responsesOf(DETAIL_PATH, "post").get("403")),
                    "the handler decides ownership of a read, because the card sits in the body");

            for (Map.Entry<String, Object> response : responsesOf(DETAIL_PATH, "post").entrySet()) {
                if (response.getKey().startsWith("2") || "401".equals(response.getKey())) {
                    continue;
                }
                assertEquals(Set.of("application/json"), contentTypesOf(response.getValue()),
                        "the read route answers " + response.getKey() + " in another shape, and"
                                + " every failure of it but the credential one, which the chain"
                                + " writes, is an error record");
            }
        }

        /**
         * Asserts every failing update answers the outcome record rather than an error record.
         *
         * <p>An update failure carries the source text of the edit or the race that produced it, and
         * the conflict answer carries the refreshed row as well, so an error record with three members
         * could not express it.
         */
        @Test
        void everyFailingUpdateAnswersTheOutcomeRecord() {
            List<String> divergent = new ArrayList<>();
            responsesOf(COLLECTION_PATH, "put").forEach((status, response) -> {
                String expected = switch (status) {
                    case "400" -> "ApiError";
                    case "401", "403" -> "Problem";
                    default -> "CardUpdateResponse";
                };
                if (!expected.equals(schemaNameOf(response))) {
                    divergent.add(status + " answers " + schemaNameOf(response) + " and not "
                            + expected);
                }
            });
            assertEquals(List.of(), divergent,
                    "the outcome carries the text and the refreshed row: " + divergent);
        }

        /** Asserts every response declares exactly one media type, so no outcome is ambiguous. */
        @Test
        void everyResponseDeclaresOneMediaType() {
            List<String> divergent = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> route : paths().entrySet()) {
                for (String method : route.getValue().keySet()) {
                    responsesOf(route.getKey(), method).forEach((status, response) -> {
                        if (contentTypesOf(response).size() != 1) {
                            divergent.add(method + " " + route.getKey() + " " + status + " declares "
                                    + contentTypesOf(response));
                        }
                    });
                }
            }
            assertEquals(List.of(), divergent, "one shape per outcome: " + divergent);
        }
    }

    /** Agreement between each schema and the record it describes. */
    @Nested
    @DisplayName("schemas against records")
    class SchemasAgainstRecords {

        /** Asserts the summary schema declares one property per component, and no other. */
        @Test
        void theSummarySchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardSummary.class), propertyNamesOf("CardSummary"),
                    "the document describes exactly the components the summary declares");
        }

        /** Asserts the list schema declares one property per component, and no other. */
        @Test
        void theListSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardListResponse.class), propertyNamesOf("CardList"),
                    "the document describes exactly the components the page declares");
        }

        /** Asserts the detail schema declares one property per component, and no other. */
        @Test
        void theDetailSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardDetailResponse.class), propertyNamesOf("CardDetail"),
                    "the document describes exactly the components the card declares");
        }

        /** Asserts the read request schema declares one property per component, and no other. */
        @Test
        void theReadRequestSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardDetailRequest.class),
                    propertyNamesOf("CardDetailRequest"),
                    "the document describes exactly the two components the search condition holds");
        }

        /** Asserts the update request schema declares one property per component, and no other. */
        @Test
        void theUpdateRequestSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardUpdateRequest.class),
                    propertyNamesOf("CardUpdateRequest"),
                    "the document describes exactly the six components the update carries");
        }

        /** Asserts the update response schema declares one property per component, and no other. */
        @Test
        void theUpdateResponseSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CardUpdateResponse.class),
                    propertyNamesOf("CardUpdateResponse"),
                    "the document describes exactly the components the outcome declares");
        }

        /** Asserts the refreshed-card schema declares one property per component, and no other. */
        @Test
        void theRefreshedCardSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(RefreshedCard.class), propertyNamesOf("RefreshedCard"),
                    "five values, and not the card verification value the source also compares");
        }

        /** Asserts the error schema declares one property per component, and no other. */
        @Test
        void theErrorSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(ApiErrorResponse.class), propertyNamesOf("ApiError"),
                    "the document describes exactly the three members the error record declares");
        }

        /**
         * Asserts the problem schema declares the four members the filter chain writes.
         *
         * <p>No record backs this schema, because {@code config/SecurityConfig} writes the body by
         * hand so that a security failure depends on no serializer configuration. The four names and
         * their order come from RFC 9457.
         */
        @Test
        void theProblemSchemaDeclaresTheFourMembersTheChainWrites() {
            assertEquals(new LinkedHashSet<>(List.of("type", "title", "status", "detail")),
                    propertyNamesOf("Problem"),
                    "config/SecurityConfig writes these four members in this order");
        }

        /**
         * Asserts the update request requires every component and the read request requires both.
         *
         * <p>Every component of an update carries a mandatory-field edit, because
         * {@code CCUP-NEW-CARDDATA} at {@code app/cbl/COCRDUPC.cbl:L307} is one fixed-width group the
         * screen submits whole. A document calling any of them optional would promise a partial
         * submit the service refuses.
         */
        @Test
        void everyRequestComponentIsRequired() {
            assertEquals(new ArrayList<>(componentNamesOf(CardUpdateRequest.class)),
                    requiredOf("CardUpdateRequest"),
                    "the screen submits one fixed-width group, so the request is whole or refused");
            assertEquals(new ArrayList<>(componentNamesOf(CardDetailRequest.class)),
                    requiredOf("CardDetailRequest"),
                    "both edits refuse an absent value, and a third rule refuses both absent");
        }

        /**
         * Asserts the outcome is the only required member of the update response.
         *
         * <p>Two outcomes carry no message and six carry no refreshed row, so the other two members
         * are absent from most answers. Requiring them would describe a body the service never sends.
         */
        @Test
        void theOutcomeIsTheOnlyRequiredMemberOfTheResponse() {
            assertEquals(List.of("outcome"), requiredOf("CardUpdateResponse"),
                    "UPDATED carries no message and only CHANGED_BEFORE_UPDATE carries a row");
        }

        /** Asserts every schema the document declares is referenced, and every reference resolves. */
        @Test
        void everySchemaIsReferencedAndEveryReferenceResolves() {
            Set<String> declared = new TreeSet<>(schemas().keySet());
            Set<String> referenced = new TreeSet<>();
            Matcher reference =
                    Pattern.compile("#/components/schemas/(\\w+)").matcher(documentText());
            while (reference.find()) {
                referenced.add(reference.group(1));
            }

            List<String> unresolved = new ArrayList<>(referenced);
            unresolved.removeAll(declared);
            assertEquals(List.of(), unresolved, "a reference resolving to nothing: " + unresolved);

            List<String> unreferenced = new ArrayList<>(declared);
            unreferenced.removeAll(referenced);
            assertEquals(List.of(), unreferenced,
                    "a schema no operation and no other schema reaches: " + unreferenced);
        }

        /** Asserts every object schema refuses a member the document does not describe. */
        @Test
        void everyObjectSchemaRefusesAnUndescribedMember() {
            List<String> permissive = new ArrayList<>();
            schemas().forEach((name, schema) -> {
                Map<String, Object> declared = asMap(schema);
                if ("object".equals(declared.get("type"))
                        && !Boolean.FALSE.equals(declared.get("additionalProperties"))) {
                    permissive.add(name);
                }
            });
            assertEquals(List.of(), permissive,
                    "a schema admitting an undescribed member describes nothing: " + permissive);
        }
    }

    /** What the document says about identifiers, and what it never says. */
    @Nested
    @DisplayName("identifiers and what never appears")
    class IdentifiersAndWhatNeverAppears {

        /**
         * Asserts every card number a response carries is masked and the cursor is a token.
         *
         * <p>{@link CardSummary} and {@link CardDetailResponse} both refuse an unmasked value in their
         * own constructors, so the document pins the same expression. A masked value names no
         * browse position, and the full card number cannot be published, so the paging cursor
         * carries the irreversible card token instead.
         */
        @Test
        void everyCardNumberAResponseCarriesIsMaskedAndTheCursorIsAToken() {
            String masked = "^\\*{12}[0-9]{4}$";
            assertEquals(masked, asMap(propertiesOf("CardSummary").get("cardNumber")).get("pattern"),
                    "a row of a page carries a masked number");
            assertEquals(masked,
                    asMap(propertiesOf("CardDetail").get("maskedCardNumber")).get("pattern"),
                    "a read carries a masked number");
            assertEquals(CardController.CURSOR_PATTERN,
                    asMap(propertiesOf("CardList").get("nextCursor")).get("pattern"),
                    "the response carries the same token shape the controller accepts");
            assertEquals(PanMasker.CARD_TOKEN_PATTERN, CardController.CURSOR_PATTERN,
                    "the cursor exposes no Primary Account Number");
        }

        /**
         * Asserts no schema of this service names the card verification value.
         *
         * <p>{@code CARD-CVV-CD PIC 9(03)} at {@code app/cpy/CVACT02Y.cpy:L7} is stored, because the
         * card record declares it, and it leaves this service in no response, no request and no
         * event.
         */
        @Test
        void noSchemaNamesTheCardVerificationValue() {
            Set<String> forbidden = Set.of("cardverificationvalue", "cvv", "cvc", "cardsecuritycode",
                    "securitycode", "cvv2");
            for (String schema : schemas().keySet()) {
                Map<String, Object> properties = asMap(asMap(schemas().get(schema))
                        .get("properties"));
                for (String property : properties.keySet()) {
                    assertFalse(forbidden.contains(property.toLowerCase(Locale.ROOT)),
                            schema + "." + property + " names the card verification value");
                }
            }
            assertFalse(documentText().toLowerCase(Locale.ROOT).contains("cvv:"),
                    "and no description declares one either");
        }

        /**
         * Asserts the error record and the problem record echo no request value.
         *
         * <p>A member named for the query string, an instance or a rejected value would carry
         * caller-supplied text back to its sender and into an access log. The route member of the
         * error record is a template, which {@link ApiErrorResponse} enforces by refusing a run of
         * digits.
         */
        @Test
        void noFailureBodyEchoesARequestValue() {
            Set<String> forbidden = Set.of("instance", "path", "query", "value", "rejectedValue",
                    "requestPath", "parameters", "submitted");
            for (String schema : List.of("ApiError", "Problem")) {
                for (String property : propertyNamesOf(schema)) {
                    assertFalse(forbidden.contains(property),
                            schema + "." + property + " would carry a request value to its sender");
                }
            }
            assertTrue(propertyNamesOf("ApiError").contains("route"),
                    "the error record carries a route template, which ApiErrorResponse enforces");
        }

        /** Asserts the account identifier keeps its leading zeros wherever it appears. */
        @Test
        void theAccountIdentifierKeepsItsLeadingZeros() {
            for (String schema : List.of("CardSummary", "CardDetail", "CardDetailRequest")) {
                Map<String, Object> account = asMap(propertiesOf(schema).get("accountId"));
                assertEquals("string", account.get("type"),
                        schema + ".accountId as a number would drop the zeros the source compares");
                assertEquals("^[0-9]{11}$", account.get("pattern"),
                        schema + ".accountId is ACCT-ID PIC 9(11), and account fifty is"
                                + " 00000000050");
            }
        }

        /**
         * Asserts the expiry travels as three text slices in every place a caller writes it.
         *
         * <p>{@code CCUP-NEW-CARDDATA} at {@code app/cbl/COCRDUPC.cbl:L307} carries a four-character
         * year, a two-character month and a two-character day, and the source edits each slice
         * separately. A single date property would collapse three edits into one and change which
         * text a caller reads.
         */
        @Test
        void theExpiryTravelsAsThreeTextSlicesWhereverACallerWritesIt() {
            for (String schema : List.of("CardUpdateRequest", "RefreshedCard")) {
                Map<String, Object> properties = propertiesOf(schema);
                assertEquals(4, asMap(properties.get("expiryYear")).get("maxLength"),
                        schema + ".expiryYear is a four-character slice");
                assertEquals(2, asMap(properties.get("expiryMonth")).get("maxLength"),
                        schema + ".expiryMonth is a two-character slice");
                assertEquals(2, asMap(properties.get("expiryDay")).get("maxLength"),
                        schema + ".expiryDay is a two-character slice");
                assertFalse(properties.containsKey("expirationDate"),
                        schema + " collapses the three slices the source edits separately");
            }
            assertEquals("date", asMap(propertiesOf("CardDetail").get("expirationDate"))
                            .get("format"),
                    "a read carries the stored DATE, which is one value and not three slices");
        }
    }

    /** Verbatim source texts the document reproduces. */
    @Nested
    @DisplayName("verbatim source texts")
    class VerbatimTexts {

        /**
         * Asserts the document enumerates the seven outcomes the Java enumeration declares, in order.
         *
         * <p>A client switches on this value, so an outcome the code can answer and the document omits
         * reaches a client that had been told it could not happen.
         */
        @Test
        void theDocumentEnumeratesTheSevenOutcomesInOrder() {
            @SuppressWarnings("unchecked")
            List<String> enumerated =
                    (List<String>) asMap(propertiesOf("CardUpdateResponse").get("outcome"))
                            .get("enum");
            assertEquals(Arrays.stream(UpdateOutcome.values()).map(Enum::name).toList(), enumerated,
                    "the document enumerates the outcomes the code declares, in declaration order");
        }

        /**
         * Asserts the document reproduces every fixed outcome text character for character.
         *
         * <p>Each of these is the text {@link UpdateOutcome#requiredMessage()} pins for an outcome, so
         * a caller reads the source's own words and a reader of the document sees them without
         * running the service.
         */
        @Test
        void theDocumentReproducesEveryFixedOutcomeText() {
            String text = documentText();
            for (UpdateOutcome outcome : UpdateOutcome.values()) {
                String required = outcome.requiredMessage();
                if (required == null) {
                    continue;
                }
                assertTrue(text.contains(required),
                        outcome + " answers with a text the document does not carry: " + required);
            }
        }

        /**
         * Asserts the document reproduces the five field-edit texts an update can answer.
         *
         * <p>These are the texts {@code 1230-EDIT-NAME}, {@code 1240-EDIT-CARDSTATUS},
         * {@code 1250-EDIT-EXPIRY-MON} and {@code 1260-EDIT-EXPIRY-YEAR} write, in the order
         * {@code app/cbl/COCRDUPC.cbl:L698-L708} performs them.
         */
        @Test
        void theDocumentReproducesTheFiveFieldEditTexts() {
            String text = documentText();
            for (String message : List.of(CardValidationMessages.PROMPT_FOR_NAME,
                    CardValidationMessages.NAME_MUST_BE_ALPHA,
                    CardValidationMessages.CARD_STATUS_MUST_BE_YES_NO,
                    CardValidationMessages.CARD_EXPIRY_MONTH_NOT_VALID,
                    CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID)) {
                assertTrue(text.contains(message),
                        "the document omits the edit text: " + message);
            }
        }

        /**
         * Asserts the document reproduces the four search-condition texts and says in what order they
         * are applied.
         *
         * <p>{@link CardController#firstSearchFailure} answers one of five texts, and the order
         * decides which, so a document listing them without the order would describe five answers and
         * predict none.
         */
        @Test
        void theDocumentReproducesTheSearchConditionTextsAndTheirOrder() {
            String text = documentText();
            for (String message : List.of(CardValidationMessages.NO_SEARCH_CRITERIA_RECEIVED,
                    CardValidationMessages.PROMPT_FOR_ACCT,
                    CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC,
                    CardValidationMessages.PROMPT_FOR_CARD,
                    CardValidationMessages.CARD_FILTER_NOT_NUMERIC)) {
                assertTrue(text.contains(message),
                        "the document omits the search text: " + message);
            }
            assertTrue(text.contains("firstSearchFailure"),
                    "the document names the method that owns the order");
            assertTrue(text.contains("L630-L640"),
                    "and cites the paragraph that performs the edits in that order");
        }

        /**
         * Asserts the document reproduces both additive expiry texts and says why they are additive.
         *
         * <p>Column {@code expiration_date} is a {@code DATE} where the source stored ten characters
         * of text, so a year, month and day naming no day of the calendar are refused here and were
         * stored there. That is the one behavioural divergence of this route, and a caller can only
         * anticipate it if the document says so.
         */
        @Test
        void theDocumentReproducesBothAdditiveExpiryTextsAndSaysWhy() {
            String text = documentText();
            assertTrue(text.contains(CardValidationMessages.ADDITIVE_CARD_EXPIRY_DAY_WIDTH),
                    "the day-width text is additive and appears in the document");
            assertTrue(
                    text.contains(CardValidationMessages.ADDITIVE_CARD_EXPIRY_NOT_A_CALENDAR_DATE),
                    "the calendar-date text is additive and appears in the document");
            assertTrue(text.contains("expiration_date is a DATE")
                            || text.contains("column expiration_date is a"),
                    "the document names the column that forces the divergence");
        }

        /**
         * Asserts the document reproduces the additive direction text.
         *
         * <p>No source text exists, because the source carries the direction as a function key and a
         * terminal cannot deliver a third one. A request can name a third value.
         */
        @Test
        void theDocumentReproducesTheAdditiveDirectionText() {
            assertTrue(documentText().contains(CardController.DIRECTION_MESSAGE),
                    "the additive direction text appears in the document");
        }

        /**
         * Asserts the document names the two paragraphs that decide the update, so a reader can find
         * the behaviour rather than infer it.
         */
        @Test
        void theDocumentCitesTheParagraphsThatDecideTheUpdate() {
            String text = documentText();
            assertTrue(text.contains("app/cbl/COCRDUPC.cbl"),
                    "the update route cites the program it reproduces");
            assertTrue(text.contains("app/cbl/COCRDSLC.cbl"),
                    "the read route cites the program it reproduces");
            assertTrue(text.contains("app/cbl/COCRDLIC.cbl"),
                    "the list route cites the program it reproduces");
        }
    }

    /**
     * Builds one response for each of the seven outcomes, through the factory each one has.
     *
     * @return the seven outcomes, one instance each
     */
    private static List<CardUpdateResponse> everyOutcome() {
        List<CardUpdateResponse> outcomes = List.of(
                CardUpdateResponse.updated(),
                CardUpdateResponse.noChangeDetected(),
                CardUpdateResponse.validationRejected(
                        CardValidationMessages.CARD_EXPIRY_YEAR_NOT_VALID),
                CardUpdateResponse.cardNotFound(),
                CardUpdateResponse.changedBeforeUpdate(
                        new RefreshedCard("ALEXANDER J MORGAN", "2030", "01", "02", "N")),
                CardUpdateResponse.lockNotAcquired(),
                CardUpdateResponse.updateFailedAfterLock());

        assertEquals(UpdateOutcome.values().length, outcomes.size(),
                "one instance per outcome, so a new outcome fails here until it is covered");
        return outcomes;
    }

    // Reading helpers. Each one narrows the parsed document and fails loudly on a missing key,
    // because a test that silently reads null proves nothing.

    /** @return the paths block */
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> paths() {
        return (Map<String, Map<String, Object>>) openApi.get("paths");
    }

    /**
     * @param path the route
     * @return the operations on one route, keyed by method
     */
    private static Map<String, Object> operationsOn(String path) {
        Map<String, Object> route = paths().get(path);
        assertTrue(route != null, DOCUMENT + " describes " + path);
        return route;
    }

    /**
     * @param path   the route
     * @param method the method
     * @return the responses of one operation, keyed by status
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> responsesOf(String path, String method) {
        Object operation = operationsOn(path).get(method);
        assertTrue(operation != null, DOCUMENT + " describes " + method + " " + path);
        return (Map<String, Object>) asMap(operation).get("responses");
    }

    /**
     * @param operation an operation block
     * @return its declared parameters, empty when it declares none
     */
    private static List<?> parametersOf(Object operation) {
        Object parameters = asMap(operation).get("parameters");
        return parameters instanceof List<?> declared ? declared : List.of();
    }

    /**
     * @param path   the route
     * @param method the method
     * @return the parameter names of one operation, in declaration order
     */
    private static List<String> parameterNamesOf(String path, String method) {
        List<String> names = new ArrayList<>();
        for (Object parameter : parametersOf(operationsOn(path).get(method))) {
            names.add(String.valueOf(asMap(parameter).get("name")));
        }
        return names;
    }

    /**
     * @param path   the route
     * @param method the method
     * @param name   the parameter name
     * @return one declared parameter
     */
    private static Map<String, Object> parameterOf(String path, String method, String name) {
        for (Object parameter : parametersOf(operationsOn(path).get(method))) {
            if (name.equals(asMap(parameter).get("name"))) {
                return asMap(parameter);
            }
        }
        throw new AssertionError(DOCUMENT + " declares no parameter " + name + " on " + method + " "
                + path);
    }

    /**
     * @param response the response block
     * @return the media types it answers with
     */
    private static Set<String> contentTypesOf(Object response) {
        Object content = asMap(response).get("content");
        return content == null ? Set.of() : asMap(content).keySet();
    }

    /**
     * Reads the schema name one response answers with, following its reference.
     *
     * @param response the response block
     * @return the schema name, or the empty string when the response carries no schema
     */
    private static String schemaNameOf(Object response) {
        Object content = asMap(response).get("content");
        if (content == null || asMap(content).isEmpty()) {
            return "";
        }
        Object body = asMap(content).values().iterator().next();
        Object reference = asMap(asMap(body).get("schema")).get("$ref");
        if (reference == null) {
            return "";
        }
        String named = reference.toString();
        return named.substring(named.lastIndexOf('/') + 1);
    }

    /** @return the security schemes block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> securitySchemes() {
        return (Map<String, Object>) components().get("securitySchemes");
    }

    /** @return the components block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> components() {
        return (Map<String, Object>) openApi.get("components");
    }

    /** @return the schemas block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemas() {
        return (Map<String, Object>) components().get("schemas");
    }

    /**
     * @param schema the schema name
     * @return the properties of one schema
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(String schema) {
        Map<String, Object> declared = asMap(schemas().get(schema));
        assertFalse(declared.isEmpty(), DOCUMENT + " declares the schema " + schema);
        return (Map<String, Object>) declared.get("properties");
    }

    /**
     * @param schema the schema name
     * @return the property names of one schema, in declaration order
     */
    private static Set<String> propertyNamesOf(String schema) {
        return new LinkedHashSet<>(propertiesOf(schema).keySet());
    }

    /**
     * @param schema the schema name
     * @return the required property names of one schema, in declaration order
     */
    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(String schema) {
        Object required = asMap(schemas().get(schema)).get("required");
        return required == null ? List.of() : (List<String>) required;
    }

    /**
     * @param type the record class
     * @return its component names, in declaration order
     */
    private static Set<String> componentNamesOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Reads the whole document as text, for the assertions that look for a verbatim sentence.
     *
     * <p>A folded scalar in the parsed tree loses its line breaks, so a search for a sentence
     * spanning two source lines has to run against the parsed value rather than the raw file.
     * Serializing the tree back to text collapses each folded scalar into one line, which is what a
     * reader of the rendered document sees.
     *
     * @return the document with every folded scalar collapsed onto one line
     */
    private static String documentText() {
        return new Yaml().dump(openApi).replaceAll("\\s+", " ");
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
