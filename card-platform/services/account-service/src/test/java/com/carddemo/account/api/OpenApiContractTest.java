package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.AccountUpdateResponse;
import com.carddemo.account.api.dto.AccountView;
import com.carddemo.account.api.dto.CustomerDataRequest;
import com.carddemo.account.api.dto.CustomerView;
import com.carddemo.account.api.dto.CycleCloseResponse;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Agreement tests between {@code src/main/resources/openapi.yaml} and the code it describes.
 *
 * <p>The description is written by hand, because no documentation generator joins the classpath. That
 * choice buys a document a reader can follow and costs a way for the two to drift, so these tests are
 * the mechanism that stops the drift. A component added to a record without a property in the document
 * fails here, and so does the reverse.
 *
 * <p>These tests read one classpath resource and use reflection. No application context, no database
 * and no broker takes part.
 */
@DisplayName("the hand-written account service description")
final class OpenApiContractTest {

    /** The hand-written description of this service. */
    private static final String DOCUMENT = "openapi.yaml";

    /** The account route, which carries a read and an update. */
    private static final String ACCOUNT_PATH = "/accounts/{accountId}";

    /** The cycle-close route. */
    private static final String CYCLE_CLOSE_PATH = "/accounts/{accountId}/cycle-close";

    /** The customer route. */
    private static final String CUSTOMER_PATH = "/customers/{customerId}";

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
         * Asserts the document describes the three routes this service serves, and no other.
         *
         * <p>{@code config/SecurityConfig} names exactly these three paths and denies every other, so
         * a route in the document that the chain does not name would be a route a caller cannot reach.
         */
        @Test
        void theDocumentDescribesTheThreeRoutes() {
            assertEquals(Set.of(ACCOUNT_PATH, CYCLE_CLOSE_PATH, CUSTOMER_PATH), paths().keySet(),
                    "config/SecurityConfig authorizes these three routes and denies the rest");
        }

        /** Asserts the account route carries the read and the update, and nothing else. */
        @Test
        void theAccountRouteCarriesAReadAndAnUpdate() {
            assertEquals(Set.of("get", "put"), operationsOn(ACCOUNT_PATH).keySet(),
                    "app/cbl/COACTVWC.cbl reads and app/cbl/COACTUPC.cbl updates");
        }

        /** Asserts the cycle-close route carries one method, and that it is not idempotent by verb. */
        @Test
        void theCycleCloseRouteCarriesOnePost() {
            assertEquals(Set.of("post"), operationsOn(CYCLE_CLOSE_PATH).keySet(),
                    "each call produces one event, so the verb is not the idempotent one");
        }

        /** Asserts the customer route carries a read alone. */
        @Test
        void theCustomerRouteCarriesAReadAlone() {
            assertEquals(Set.of("get"), operationsOn(CUSTOMER_PATH).keySet(),
                    "a customer is updated through the account route, in one transaction with it");
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
            List<Map<String, Object>> security = (List<Map<String, Object>>) openApi.get("security");
            assertEquals(List.of("basicAuth"), new ArrayList<>(security.get(0).keySet()),
                    "config/SecurityConfig installs HTTP Basic authentication");
            assertTrue(securitySchemes().containsKey("basicAuth"),
                    "the scheme the requirement names is defined");
        }
    }

    /** The status codes each operation answers with. */
    @Nested
    @DisplayName("status codes")
    class StatusCodes {

        /** Asserts the read answers the six codes it can answer. */
        @Test
        void theAccountReadAnswersSixCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "422", "500"),
                    responsesOf(ACCOUNT_PATH, "get").keySet(),
                    "a read has no body to malform and no race to lose");
        }

        /**
         * Asserts the update answers eight codes, the two a read cannot.
         *
         * <p>{@code 400} is a body that could not be read, and {@code 409} is a lost race. Both are
         * outcomes only a write reaches.
         */
        @Test
        void theAccountUpdateAnswersEightCodes() {
            assertEquals(Set.of("200", "400", "401", "403", "404", "409", "422", "500"),
                    responsesOf(ACCOUNT_PATH, "put").keySet(),
                    "a write adds an unreadable body and a lost race to what a read can answer");
        }

        /** Asserts the cycle close answers six codes, carrying no body to malform. */
        @Test
        void theCycleCloseAnswersSixCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "422", "500"),
                    responsesOf(CYCLE_CLOSE_PATH, "post").keySet(),
                    "the call carries no body, so no body can be unreadable");
        }

        /** Asserts the customer read answers the same six codes the account read does. */
        @Test
        void theCustomerReadAnswersSixCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "422", "500"),
                    responsesOf(CUSTOMER_PATH, "get").keySet(),
                    "the two reads answer alike");
        }

        /**
         * Asserts every failure answers a problem document and every success answers JSON.
         *
         * <p>One service answering two shapes of error would make a client parse both.
         * {@code config/SecurityConfig} already writes {@code application/problem+json} for
         * {@code 401} and {@code 403}, so every other failure writes the same.
         */
        @Test
        void everyFailureAnswersOneProblemShape() {
            List<String> divergent = new ArrayList<>();
            for (Map.Entry<String, Map<String, Object>> route : paths().entrySet()) {
                for (String method : route.getValue().keySet()) {
                    responsesOf(route.getKey(), method).forEach((status, response) -> {
                        String expected = status.startsWith("2") ? "application/json"
                                : "application/problem+json";
                        Set<String> types = contentTypesOf(response);
                        if (!types.equals(Set.of(expected))) {
                            divergent.add(method + " " + route.getKey() + " " + status + " answers "
                                    + types + " and not [" + expected + "]");
                        }
                    });
                }
            }
            assertEquals(List.of(), divergent, "one shape per outcome class: " + divergent);
        }
    }

    /** Agreement between each schema and the record it describes. */
    @Nested
    @DisplayName("schemas against records")
    class SchemasAgainstRecords {

        /** Asserts the account view schema declares one property per component, and no other. */
        @Test
        void theAccountViewSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(AccountView.class), propertyNamesOf("AccountView"),
                    "the document describes exactly the components the view declares");
        }

        /** Asserts the customer view schema declares one property per component, and no other. */
        @Test
        void theCustomerViewSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CustomerView.class), propertyNamesOf("CustomerView"),
                    "the document describes exactly the components the view declares");
        }

        /** Asserts the update request schema declares one property per component, and no other. */
        @Test
        void theUpdateRequestSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(AccountUpdateRequest.class),
                    propertyNamesOf("AccountUpdateRequest"),
                    "the document describes exactly the components the request declares");
        }

        /** Asserts the account data schema declares one property per component, and no other. */
        @Test
        void theAccountDataSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(AccountDataRequest.class),
                    propertyNamesOf("AccountDataRequest"),
                    "the document describes exactly the ten account components the request declares");
        }

        /** Asserts the customer data schema declares one property per component, and no other. */
        @Test
        void theCustomerDataSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CustomerDataRequest.class),
                    propertyNamesOf("CustomerDataRequest"),
                    "the document describes exactly the twenty components the request declares");
        }

        /** Asserts the update response schema declares one property per component, and no other. */
        @Test
        void theUpdateResponseSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(AccountUpdateResponse.class),
                    propertyNamesOf("AccountUpdateResponse"),
                    "the document describes exactly the components the response declares");
        }

        /** Asserts the cycle-close schema declares one property per component, and no other. */
        @Test
        void theCycleCloseSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(CycleCloseResponse.class),
                    propertyNamesOf("CycleCloseResponse"),
                    "the document describes exactly the three components the response declares");
        }

        /** Asserts the problem schema declares one property per component, and no other. */
        @Test
        void theProblemSchemaMatchesItsRecord() {
            assertEquals(componentNamesOf(ApiProblem.class), propertyNamesOf("ApiProblem"),
                    "the document describes exactly the members the problem record declares");
        }

        /**
         * Asserts the request requires the one block this service cannot supply itself.
         *
         * <p>An account record declares no customer identifier, at
         * {@code app/cpy/CVACT01Y.cpy:L4-L17}, and this service owns no cross-reference table, so the
         * caller has to name the customer.
         */
        @Test
        void theRequestRequiresTheCustomerBlock() {
            assertEquals(List.of("customerData"), requiredOf("AccountUpdateRequest"),
                    "an account record names no customer, so the caller does");
        }

        /**
         * Asserts each block's required list is exactly the components whose bean constraints refuse an
         * absent value.
         *
         * <p>This is the assertion that stops the document describing a contract the code does not have.
         * A component with a mandatory-field edit refuses {@code null} exactly as it refuses a blank, so
         * a document calling it optional would promise a partial body the service refuses. The two lists
         * were read by running the validator over an empty block rather than by reading the annotations,
         * because a cross-field check contributes a requirement no single annotation shows.
         */
        @Test
        void eachBlockRequiresTheComponentsItsEditsRefuseAbsent() {
            assertEquals(new TreeSet<>(List.of("activeStatus", "currentBalance", "creditLimit",
                            "cashCreditLimit", "openDate", "expirationDate", "reissueDate",
                            "currentCycleCredit", "currentCycleDebit")),
                    new TreeSet<>(requiredOf("AccountDataRequest")),
                    "nine of ten account components carry a mandatory-field edit; groupId does not");

            assertEquals(new TreeSet<>(List.of("customerId", "firstName", "lastName", "addressLine1",
                            "addressCity", "addressStateCode", "addressCountryCode", "addressZip",
                            "socialSecurityPart1", "socialSecurityPart2", "socialSecurityPart3",
                            "eftAccountId", "primaryCardHolderIndicator", "ficoCreditScore")),
                    new TreeSet<>(requiredOf("CustomerDataRequest")),
                    "ten mandatory edits, the identifier, and the three parts one cross-field check"
                            + " requires together");
        }

        /**
         * Asserts the document says a block that is present has to be complete.
         *
         * <p>A reader who takes the merge rule to mean any component may be omitted will send a partial
         * block and read a 422 carrying ten messages. Saying so once, where the operation is described,
         * is what prevents that.
         */
        @Test
        void theDocumentSaysAPresentBlockHasToBeComplete() {
            String update = String.valueOf(operationsOn(ACCOUNT_PATH).get("put").get("description"))
                    .replaceAll("\\s+", " ");

            assertTrue(update.contains("has to be complete"),
                    "the operation says a present block is a whole-record submit");
            assertTrue(update.contains("omit is a whole block"),
                    "and says what a caller may leave out instead");
        }
    }

    /** Properties of the document that protect fixed-point arithmetic and cardholder data. */
    @Nested
    @DisplayName("money, identifiers and what never appears")
    class MoneyAndPrivacy {

        /**
         * Asserts every monetary property travels as a string constrained to two fractional digits.
         *
         * <p>Most parsers read a JSON number into a binary floating-point value, which is the one
         * thing a platform whose correctness rests on fixed-point arithmetic cannot allow. Every
         * account amount is {@code PIC S9(10)V99}, so two fractional digits are the whole contract.
         */
        @Test
        void everyAmountTravelsAsAStringWithTwoFractionalDigits() {
            List<String> amounts = List.of("currentBalance", "creditLimit", "cashCreditLimit",
                    "currentCycleCredit", "currentCycleDebit");
            List<String> divergent = new ArrayList<>();
            for (String schema : List.of("AccountView", "CycleCloseResponse")) {
                Map<String, Object> properties = propertiesOf(schema);
                for (String amount : amounts) {
                    if (!properties.containsKey(amount)) {
                        continue;
                    }
                    Map<String, Object> property = asMap(properties.get(amount));
                    if (!"string".equals(property.get("type"))) {
                        divergent.add(schema + "." + amount + " is not a string");
                    }
                    Object pattern = property.get("pattern");
                    if (pattern == null || !pattern.toString().endsWith("[0-9]{2}$")) {
                        divergent.add(schema + "." + amount + " does not pin two fractional digits");
                    }
                }
            }
            assertEquals(List.of(), divergent, "a JSON number would reintroduce binary floating"
                    + " point into a fixed-point system: " + divergent);
        }

        /**
         * Asserts both identifiers keep their leading zeros.
         *
         * <p>The source compares an identifier as text, so account fifty is {@code 00000000050}. An
         * integer property would drop the zeros and no comparison would match afterwards.
         */
        @Test
        void bothIdentifiersKeepTheirLeadingZeros() {
            assertEquals("^[0-9]{11}$", asMap(parameterOf("AccountId").get("schema")).get("pattern"),
                    "ACCT-ID PIC 9(11) at app/cpy/CVACT01Y.cpy:L5");
            assertEquals("^[0-9]{9}$", asMap(parameterOf("CustomerId").get("schema")).get("pattern"),
                    "CUST-ID PIC 9(09) at app/cpy/CVCUS01Y.cpy:L5");
        }

        /**
         * Asserts the credit score is bounded exactly where the source bounds it.
         *
         * <p>{@code app/cbl/COACTUPC.cbl} accepts 300 through 850 and rejects everything else with one
         * message, which the document reproduces character for character.
         */
        @Test
        void theCreditScoreCarriesTheRangeTheSourceEnforces() {
            Map<String, Object> score = asMap(propertiesOf("CustomerView").get("ficoCreditScore"));
            assertEquals(300, score.get("minimum"), "the lowest passing score");
            assertEquals(850, score.get("maximum"), "the highest passing score");
            assertTrue(documentText().contains(CustomerDataRequest.FICO_RANGE_MESSAGE),
                    "the document reproduces the range message character for character");
        }

        /**
         * Asserts the document names no Social Security number and no card verification value in a
         * response.
         *
         * <p>The customer view carries neither the recombined Social Security column nor the
         * government-issued identifier, and no schema of this service carries a card number at all,
         * because the card record belongs to the card service.
         */
        @Test
        void noResponseSchemaDisclosesAnIdentityDocument() {
            Set<String> view = propertyNamesOf("CustomerView");
            assertFalse(view.contains("socialSecurityNumber"),
                    "a read is the wrong operation to disclose a Social Security number through");
            assertFalse(view.contains("governmentIssuedId"),
                    "and the wrong operation for a government-issued identifier");
            Set<String> ownedByTheCardService = Set.of("cardnumber", "maskedcardnumber",
                    "cardnum", "pan", "cardverificationvalue", "cvv", "cardexpirationdate",
                    "embossedname");
            for (String schema : List.of("AccountView", "CustomerView", "CycleCloseResponse",
                    "AccountUpdateResponse")) {
                for (String property : propertyNamesOf(schema)) {
                    assertFalse(
                            ownedByTheCardService.contains(
                                    property.toLowerCase(java.util.Locale.ROOT)),
                            schema + "." + property + " names a field of CARD-RECORD at"
                                    + " app/cpy/CVACT02Y.cpy, which the card service owns, and no"
                                    + " response of this service carries one");
                }
            }
            assertTrue(propertyNamesOf("CustomerView").contains("primaryCardHolderIndicator"),
                    "CUST-PRI-CARD-HOLDER-IND PIC X(01) at app/cpy/CVCUS01Y.cpy:L22 belongs to the"
                            + " customer record and stays, however much its name reads like a card"
                            + " field");
        }

        /**
         * Asserts the problem schema declares nothing that could echo a request value.
         *
         * <p>A member named for the path, the query string, an instance or a rejected value would
         * carry caller-supplied text back to the caller and into an access log.
         */
        @Test
        void theProblemSchemaEchoesNoRequestValue() {
            for (String property : propertyNamesOf("ApiProblem")) {
                assertFalse(Set.of("instance", "path", "query", "value", "rejectedValue",
                                "requestPath", "parameters")
                        .contains(property),
                        property + " would carry a request value back to its sender");
            }
        }
    }

    /** Verbatim source texts the document reproduces. */
    @Nested
    @DisplayName("verbatim source texts")
    class VerbatimTexts {

        /**
         * Asserts the document reproduces the two outcome texts an update answers with.
         *
         * <p>The no-change text comes from {@code app/cbl/COACTUPC.cbl:L1463-L1467} and the applied
         * text is what this service answers when both rows were written. A caller reads one of the two
         * and can tell which happened.
         */
        @Test
        void theDocumentReproducesBothUpdateOutcomeTexts() {
            String text = documentText();
            assertTrue(text.contains(AccountController.UPDATE_APPLIED_MESSAGE),
                    "the applied text appears in the document");
            assertTrue(text.contains("No change detected with respect to values fetched."),
                    "app/cbl/COACTUPC.cbl:L1463-L1467 produces this text");
        }

        /**
         * Asserts the document reproduces the changed-record text the conflict answer carries.
         *
         * <p>{@code app/cbl/COACTUPC.cbl:L3950-L3952} writes it, and it is the one text that tells a
         * caller to read the row again rather than correct a field.
         */
        @Test
        void theDocumentReproducesTheChangedRecordText() {
            assertTrue(documentText().contains(ConcurrentChangeDetector.RECORD_CHANGED_MESSAGE),
                    "the conflict answer carries the source text character for character");
        }

        /**
         * Asserts the document reproduces the mandatory-address text.
         *
         * <p>It is the one edit message of the customer block that a body can trigger by omission
         * rather than by a malformed value, so it is the clearest example the document can carry.
         */
        @Test
        void theDocumentReproducesTheMandatoryAddressText() {
            assertTrue(documentText().contains(CustomerDataRequest.ADDRESS_LINE_1_REQUIRED_MESSAGE),
                    "app/cbl/COACTUPC.cbl writes this text for a blank first address line");
        }

        /**
         * Asserts every title the code can write is enumerated in the document.
         *
         * <p>The five this service's own handlers write plus the two
         * {@code config/SecurityConfig} writes. A title the code writes and the document omits would
         * reach a client that had been told it could not.
         */
        @Test
        void theDocumentEnumeratesEveryProblemTitle() {
            @SuppressWarnings("unchecked")
            List<String> enumerated =
                    (List<String>) asMap(propertiesOf("ApiProblem").get("title")).get("enum");
            assertEquals(new TreeSet<>(List.of(ApiProblem.VALIDATION_FAILED, ApiProblem.NOT_FOUND,
                            ApiProblem.CONFLICT, ApiProblem.MALFORMED_REQUEST,
                            ApiProblem.INTERNAL_FAILURE, "Unauthorized", "Forbidden")),
                    new TreeSet<>(enumerated),
                    "five titles from this service's handlers and two from its security chain");
        }

        /**
         * Asserts the document names the two lock texts the conflict answer can also carry.
         *
         * <p>{@link AccountUpdateService#lockFailureMessages()} is what the controller compares
         * against, so the document has to describe the same outcome the code routes to {@code 409}.
         */
        @Test
        void theDocumentDescribesTheLockFailureOutcome() {
            assertEquals(2, AccountUpdateService.lockFailureMessages().size(),
                    "two rows are locked, so two lock failures are possible");
            String conflict = String.valueOf(responsesOf(ACCOUNT_PATH, "put").get("409"));
            assertTrue(conflict.contains("L3907-L3915"),
                    "the account lock failure is cited at its source line");
            assertTrue(conflict.contains("L3934-L3942"),
                    "the customer lock failure is cited at its source line");
        }
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
    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> operationsOn(String path) {
        Map<String, Object> route = paths().get(path);
        assertTrue(route != null, DOCUMENT + " describes " + path);
        return (Map<String, Map<String, Object>>) (Map<String, ?>) route;
    }

    /**
     * @param path   the route
     * @param method the method
     * @return the responses of one operation, keyed by status
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> responsesOf(String path, String method) {
        Map<String, Object> operation = operationsOn(path).get(method);
        assertTrue(operation != null, DOCUMENT + " describes " + method + " " + path);
        return (Map<String, Object>) operation.get("responses");
    }

    /**
     * Reads the media types one response answers with, following a reference when it holds one.
     *
     * @param response the response block, or a reference to one
     * @return the media types
     */
    private static Set<String> contentTypesOf(Object response) {
        Map<String, Object> resolved = asMap(response);
        Object reference = resolved.get("$ref");
        if (reference != null) {
            String name = reference.toString().substring(reference.toString().lastIndexOf('/') + 1);
            resolved = asMap(sharedResponses().get(name));
        }
        Object content = resolved.get("content");
        return content == null ? Set.of() : asMap(content).keySet();
    }

    /** @return the reusable responses block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sharedResponses() {
        return (Map<String, Object>) components().get("responses");
    }

    /** @return the security schemes block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> securitySchemes() {
        return (Map<String, Object>) components().get("securitySchemes");
    }

    /**
     * @param name the parameter name
     * @return one reusable parameter
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameterOf(String name) {
        Map<String, Object> parameter =
                asMap(((Map<String, Object>) components().get("parameters")).get(name));
        assertTrue(!parameter.isEmpty(), DOCUMENT + " declares the parameter " + name);
        return parameter;
    }

    /** @return the components block */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> components() {
        return (Map<String, Object>) openApi.get("components");
    }

    /**
     * @param schema the schema name
     * @return the properties of one schema
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertiesOf(String schema) {
        Map<String, Object> schemas = (Map<String, Object>) components().get("schemas");
        Map<String, Object> declared = asMap(schemas.get(schema));
        assertTrue(!declared.isEmpty(), DOCUMENT + " declares the schema " + schema);
        return (Map<String, Object>) declared.get("properties");
    }

    /**
     * @param schema the schema name
     * @return the property names of one schema
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
        Map<String, Object> schemas = (Map<String, Object>) components().get("schemas");
        Object required = asMap(schemas.get(schema)).get("required");
        return required == null ? List.of() : (List<String>) required;
    }

    /**
     * @param type the record class
     * @return its component names, in declaration order
     */
    private static Set<String> componentNamesOf(Class<?> type) {
        return Arrays.stream(type.getRecordComponents())
                .map(RecordComponent::getName)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Reads the whole document as text, for the assertions that look for a verbatim sentence.
     *
     * <p>A folded scalar in the parsed tree loses its line breaks, so a search for a sentence spanning
     * two source lines has to run against the parsed value rather than the raw file. Serializing the
     * tree back to text collapses each folded scalar into one line, which is what a reader of the
     * rendered document sees.
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
