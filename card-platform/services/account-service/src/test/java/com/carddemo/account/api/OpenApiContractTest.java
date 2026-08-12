package com.carddemo.account.api;

import static org.junit.jupiter.api.Assertions.assertAll;
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
import com.carddemo.account.config.CrossSiteRequestFilter;
import com.carddemo.account.config.RequestRateCeilingFilter;
import com.carddemo.account.domain.AccountUpdateService;
import com.carddemo.account.domain.ConcurrentChangeDetector;
import java.io.InputStream;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

    /** Each documented schema paired with the record whose components it describes. */
    private static final Map<String, Class<?>> RECORD_BACKED_SCHEMAS = Map.of(
            "AccountView", AccountView.class,
            "CustomerView", CustomerView.class,
            "AccountUpdateRequest", AccountUpdateRequest.class,
            "AccountDataRequest", AccountDataRequest.class,
            "CustomerDataRequest", CustomerDataRequest.class,
            "AccountUpdateResponse", AccountUpdateResponse.class,
            "CycleCloseResponse", CycleCloseResponse.class,
            "ApiProblem", ApiProblem.class);

    /** Properties the pairings above cover, asserted so a shrunken map cannot pass unnoticed. */
    private static final int RECORD_BACKED_PROPERTIES = 69;

    /** The account route, which carries a read and an update. */
    private static final String ACCOUNT_PATH = "/accounts/{accountId}";

    private static final String CYCLE_CLOSE_PATH = "/accounts/{accountId}/cycle-close";

    private static final String CUSTOMER_PATH = "/customers/{customerId}";

    /**
     * The one status that carries no body.
     *
     * <p>{@link AccountApiExceptionHandler#onUnsupportedRequest} returns the status alone for
     * {@code 406}: a caller that accepts no media type this service writes cannot be sent a problem
     * document either.
     */
    private static final String BODILESS_STATUS = "406";

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

        /**
         * Asserts the read answers the nine codes it can answer.
         *
         * <p>{@code 405} and {@code 406} are the protocol refusals
         * {@link AccountApiExceptionHandler#onUnsupportedRequest} answers, keeping the framework
         * status and, on a {@code 405}, the {@code Allow} header. {@code 415} is absent because a read
         * carries no body to type. {@code 429} is the rate ceiling
         * {@code config/RequestRateCeilingFilter} applies ahead of the chain.
         */
        @Test
        void theAccountReadAnswersNineCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "405", "406", "422", "429", "500"),
                    responsesOf(ACCOUNT_PATH, "get").keySet(),
                    "a read has no body to malform and no race to lose");
        }

        /**
         * Asserts the update answers twelve codes, the three a read cannot.
         *
         * <p>{@code 400} is a body that could not be read, {@code 409} is a lost race and {@code 415}
         * is a body typed as something this route does not read. All three are outcomes only an
         * operation carrying a body reaches.
         */
        @Test
        void theAccountUpdateAnswersTwelveCodes() {
            assertEquals(Set.of("200", "400", "401", "403", "404", "405", "406", "409", "415",
                            "422", "429", "500"),
                    responsesOf(ACCOUNT_PATH, "put").keySet(),
                    "a write adds an unreadable body, a lost race and a media type this route does"
                            + " not read to what a read can answer");
        }

        /**
         * Asserts the cycle close answers ten codes, carrying no body to malform.
         *
         * <p>{@code 400} is absent because no body is read, and {@code 415} is present for the
         * opposite reason: the route requires {@code application/json} as its cross-site request
         * forgery control, so a call naming no media type is refused before the accumulators are
         * touched. {@code 405} and {@code 406} are the protocol answers every route of this service
         * carries.
         */
        @Test
        void theCycleCloseAnswersTenCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "405", "406", "415", "422", "429", "500"),
                    responsesOf(CYCLE_CLOSE_PATH, "post").keySet(),
                    "the call carries no body to malform and still names a media type it requires");
        }

        /** Asserts the customer read answers the same nine codes the account read does. */
        @Test
        void theCustomerReadAnswersNineCodes() {
            assertEquals(Set.of("200", "401", "403", "404", "405", "406", "422", "429", "500"),
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
                        Set<String> types = contentTypesOf(response);
                        if (BODILESS_STATUS.equals(status)) {
                            if (!types.isEmpty()) {
                                divergent.add(method + " " + route.getKey() + " " + status
                                        + " answers " + types + " and a caller that accepts none of"
                                        + " them can read none of them");
                            }
                            return;
                        }
                        String expected = status.startsWith("2") ? "application/json"
                                : "application/problem+json";
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

        /**
         * Asserts every property of a response schema is required.
         *
         * <p>A record serializes every component, so each of these properties is on the wire on every
         * response. Every column the three projections read is declared {@code NOT NULL} by
         * {@code src/main/resources/db/migration/V1__schema.sql}, so none of them can be null either.
         * A property left out of {@code required} tells a caller it may be absent, and a caller that
         * believes that writes a branch it will never reach.
         */
        @Test
        void everyPropertyOfAResponseSchemaIsRequired() {
            for (String schema : List.of("AccountView", "CustomerView", "CycleCloseResponse")) {
                assertEquals(propertyNamesOf(schema), new LinkedHashSet<>(requiredOf(schema)),
                        schema + " serializes every component and holds no nullable column");
            }
        }

        /**
         * Asserts the update response requires both members and admits the null one of them can hold.
         *
         * <p>{@code api/AccountController.answerOf} reads the account row again and passes
         * {@code null} when that read finds none, and a record serializes the component either way.
         * A schema that referenced {@link AccountView} alone would declare a body the service can
         * write invalid.
         *
         * <p>The union is spelled as {@code type: 'null'} beside the reference, which is what JSON
         * Schema 2020-12 defines and what the {@code openapi: 3.1.0} version of this document admits.
         * The {@code nullable} keyword of earlier versions appears nowhere.
         */
        @Test
        void theUpdateResponseRequiresBothMembersAndAdmitsANullAccount() {
            assertEquals(List.of("message", "account"), requiredOf("AccountUpdateResponse"),
                    "both members reach the wire on every 200");

            Map<String, Object> account =
                    asMap(propertiesOf("AccountUpdateResponse").get("account"));
            assertFalse(account.containsKey("$ref"),
                    "a bare reference would refuse the null this member can carry");
            List<?> alternatives = (List<?>) account.get("oneOf");
            assertEquals(List.of(Map.of("$ref", "#/components/schemas/AccountView"),
                            Map.of("type", "null")),
                    alternatives, "the view or a null, and nothing else");

            assertFalse(documentText().contains("nullable"),
                    "nullable is not a keyword of JSON Schema 2020-12");
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
         * Asserts every documented property declares the wire type its component serializes to, and
         * that every monetary component is actually sent as text.
         *
         * <p>The tests above hold each schema to the <em>names</em> its record declares. None held it
         * to the types, so a property could describe a number where the record sends text. That gap is
         * not theoretical here: publishing {@code AccountDataRequest.creditLimit} as a number passed
         * every test in this module, and a credit limit read into a double is the value the overlimit
         * rule at {@code app/cbl/CBTRN02C.cbl:L403-L413} compares against.
         *
         * <p>The monetary claim has two halves, and the second is the one a document cannot show. Five
         * components of the account view and two of the cycle-close response are {@code BigDecimal},
         * and they reach the wire as text only because each carries
         * {@code @JsonSerialize(using = ToStringSerializer.class)}. Removing that annotation would
         * send a JSON number while this document still said string, so the annotation is asserted
         * rather than assumed. {@code ACCT-CURR-BAL PIC S9(10)V99} at {@code app/cpy/CVACT01Y.cpy:L7}
         * carries twelve digits, which is beyond what a double holds exactly.
         */
        @Test
        void everyDocumentedPropertyDeclaresItsWireType() {
            List<String> mismatches = new ArrayList<>();
            int compared = 0;
            for (Map.Entry<String, Class<?>> backed : RECORD_BACKED_SCHEMAS.entrySet()) {
                Map<String, Object> properties = propertiesOf(backed.getKey());
                for (RecordComponent component : backed.getValue().getRecordComponents()) {
                    String property = component.getName();
                    if (!properties.containsKey(property)) {
                        mismatches.add(backed.getKey() + " publishes no " + property);
                        continue;
                    }
                    compared++;
                    if (component.getType() == java.math.BigDecimal.class
                            && !sentAsText(component)) {
                        mismatches.add(backed.getKey() + "." + property + " is a BigDecimal carrying"
                                + " no ToStringSerializer, so it reaches the wire as a number"
                                + " whatever this document declares");
                        continue;
                    }
                    Object declared = asMap(properties.get(property)).get("type");
                    if (!declares(declared, wireTypeOf(component))) {
                        mismatches.add(backed.getKey() + "." + property + " declares " + declared
                                + " for a " + component.getType().getSimpleName());
                    }
                }
            }

            assertEquals(List.of(), mismatches,
                    "properties whose declared type is not the type their component sends");
            assertEquals(RECORD_BACKED_PROPERTIES, compared,
                    "properties compared, so a pairing dropped from the map fails here");
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
         * Asserts each documented pattern is the pattern its controller constrains.
         *
         * <p>The assertion above compares the document against a literal, so the document and the
         * test could agree with each other while both drifted from the code. These compare it against
         * the constants the handlers annotate their path variables with, which is the only comparison
         * that fails when a controller changes.
         *
         * <p>All three routes carrying an account identifier are covered.
         * {@code BillingCycleController} takes its constant from {@code AccountController} rather than
         * restating it, and that is asserted rather than assumed, because a restated copy is what
         * would let one route drift from the other two.
         */
        @Test
        void everyDocumentedPatternIsTheOneItsControllerConstrains() {
            String documentedAccount =
                    String.valueOf(asMap(parameterOf("AccountId").get("schema")).get("pattern"));
            String documentedCustomer =
                    String.valueOf(asMap(parameterOf("CustomerId").get("schema")).get("pattern"));

            assertAll("the documented patterns against the handlers",
                    () -> assertEquals(AccountController.ACCOUNT_ID_PATTERN, documentedAccount,
                            "the account pattern the read and update handlers constrain"),
                    () -> assertEquals(CustomerController.CUSTOMER_ID_PATTERN, documentedCustomer,
                            "the customer pattern the customer handler constrains"),
                    () -> assertEquals(AccountController.ACCOUNT_ID_PATTERN,
                            BillingCycleController.ACCOUNT_ID_PATTERN,
                            "the cycle-close route reuses the account pattern rather than restating"
                                    + " it, so all three routes cannot drift apart"),
                    () -> assertTrue("00000000050".matches(documentedAccount),
                            "the seeded account passes its own pattern"),
                    () -> assertFalse("50".matches(documentedAccount),
                            "an identifier stripped of its leading zeros does not"),
                    () -> assertTrue("000000050".matches(documentedCustomer),
                            "the seeded customer passes its own pattern"),
                    () -> assertFalse("00000000050".matches(documentedCustomer),
                            "an eleven-digit value does not pass the nine-digit pattern"));
        }

        /**
         * Asserts every operation declares the shared parameter its own path names.
         *
         * <p>Each route carries exactly one path parameter, referenced rather than restated, so the
         * pattern asserted above governs every one of them. An operation that dropped its reference,
         * or referenced the wrong component, would take its identifier unconstrained while the shared
         * definitions above still looked correct.
         *
         * <p>Only referenced parameters are read here. The two state-changing operations also declare
         * the first-party request header inline, because that header is a requirement of this service
         * rather than a shared component, and {@link #stateChangingOperationsDeclareTheRequestHeader}
         * is what holds it to the filter that enforces it.
         */
        @Test
        void everyOperationReferencesTheSharedParameterItsPathNames() {
            Map<String, String> expected = Map.of(
                    "GET /accounts/{accountId}", "#/components/parameters/AccountId",
                    "PUT /accounts/{accountId}", "#/components/parameters/AccountId",
                    "POST /accounts/{accountId}/cycle-close", "#/components/parameters/AccountId",
                    "GET /customers/{customerId}", "#/components/parameters/CustomerId");

            assertEquals(new TreeMap<>(expected), new TreeMap<>(declaredParameterReferences()),
                    "each operation references the shared parameter its path names");
        }

        /**
         * Asserts the two state-changing operations document the header their chain requires.
         *
         * <p>{@code config/CrossSiteRequestFilter} refuses a state-changing request that carries no
         * {@value com.carddemo.account.config.CrossSiteRequestFilter#DEFAULT_REQUIRED_HEADER}, and it
         * refuses it with 403 before any route rule is consulted. A document that omitted the header
         * would describe a call that cannot succeed, and a reader following it would receive a
         * refusal naming a control the document never mentioned. The two reads declare no such
         * header, because the filter passes every safe method through.
         */
        @Test
        void stateChangingOperationsDeclareTheRequestHeader() {
            assertEquals(Map.of(
                            "PUT /accounts/{accountId}", true,
                            "POST /accounts/{accountId}/cycle-close", true,
                            "GET /accounts/{accountId}", false,
                            "GET /customers/{customerId}", false),
                    declaredRequestHeaders(),
                    "the header the chain requires is documented on exactly the operations that"
                            + " require it");
        }

        /**
         * Asserts the credit score is bounded exactly where the source bounds it.
         *
         * <p>{@code 88 FICO-RANGE-IS-VALID VALUES 300 THROUGH 850} at
         * {@code app/cbl/COACTUPC.cbl:L848-L849} is reached from {@code 1275-EDIT-FICO-SCORE}, which
         * only {@code 1200-EDIT-MAP-INPUTS} performs. {@code app/cbl/COACTVWC.cbl:L505-L506} moves a
         * stored score to the screen and tests nothing, and 21 of the 50 rows of
         * {@code app/data/ASCII/custdata.txt} carry a score below 300. A read schema bounded at 300
         * would declare 21 of the 50 seeded rows unrepresentable.
         *
         * <p>The read schema therefore carries the bounds {@code PIC 9(03)} holds, and the range
         * message stays in the document for the update path that emits it.
         */
        @Test
        void theCreditScoreCarriesTheRangeTheSourceEnforces() {
            Map<String, Object> score = asMap(propertiesOf("CustomerView").get("ficoCreditScore"));
            assertEquals(0, score.get("minimum"), "the lowest value PIC 9(03) holds");
            assertEquals(999, score.get("maximum"), "the highest value PIC 9(03) holds");
            assertTrue(documentText().contains(CustomerDataRequest.FICO_RANGE_MESSAGE),
                    "the document reproduces the range message character for character");

            Map<String, Object> submitted =
                    asMap(propertiesOf("CustomerDataRequest").get("ficoCreditScore"));
            assertTrue(String.valueOf(submitted.get("description")).contains("300 and 850"),
                    "the update path is where the source range applies");
            assertTrue(String.valueOf(score.get("description")).replaceAll("\\s+", " ")
                            .contains("A read answers the stored value unchanged"),
                    "and the read path says a stored value arrives as stored");
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
                    "CUST-PRI-CARD-HOLDER-IND PIC X(01) at app/cpy/CVCUS01Y.cpy:L21 belongs to the"
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
         * <p>Ten titles reach a caller. Five are constants of {@link ApiProblem} and two are the ones
         * {@code config/SecurityConfig} writes. Two are reason phrases:
         * {@link AccountApiExceptionHandler#onUnsupportedRequest} reads the title from the status the
         * framework named rather than from a constant, so a {@code 405} and a {@code 415} carry the
         * reason phrase of their own status and its capitalization. {@code 406} contributes none,
         * because it carries no body. The tenth is
         * {@link RequestRateCeilingFilter#REFUSAL_TITLE}, which a filter ahead of the security chain
         * writes, so it can arrive before any handler of this service is reached.
         *
         * <p>Every phrase is read from a constant or from {@link HttpStatus} rather than written out,
         * so a title this document publishes cannot drift from the one the code supplies. A title the
         * code writes and the document omits reaches a client that had been told it could not.
         */
        @Test
        void theDocumentEnumeratesEveryProblemTitle() {
            @SuppressWarnings("unchecked")
            List<String> enumerated =
                    (List<String>) asMap(propertiesOf("ApiProblem").get("title")).get("enum");
            assertEquals(new TreeSet<>(List.of(ApiProblem.VALIDATION_FAILED, ApiProblem.NOT_FOUND,
                            ApiProblem.CONFLICT, ApiProblem.MALFORMED_REQUEST,
                            ApiProblem.INTERNAL_FAILURE, "Unauthorized", "Forbidden",
                            HttpStatus.METHOD_NOT_ALLOWED.getReasonPhrase(),
                            HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase(),
                            RequestRateCeilingFilter.REFUSAL_TITLE)),
                    new TreeSet<>(enumerated),
                    "five titles from this service's handlers, two from its security chain, two"
                            + " reason phrases from the protocol arm and one from the rate ceiling");
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
     * Returns the JSON type a property describing {@code component} must declare.
     *
     * <p>An unmapped type is an error rather than a default. Letting one fall through to a number is
     * the drift this method exists to prevent, so a component type nobody has considered stops the
     * build with a message naming it. A {@code BigDecimal} reaching here has already been shown to
     * carry a to-text serializer, which is why it maps to a string.
     *
     * @param component the record component the property describes
     * @return the type the document must declare, or {@code null} where the component is itself a
     *     record and the property is therefore a reference to another schema
     */
    private static String wireTypeOf(RecordComponent component) {
        Class<?> type = component.getType();
        if (type == String.class || type.isEnum() || type == java.math.BigDecimal.class) {
            return "string";
        }
        if (type == int.class || type == Integer.class) {
            return "integer";
        }
        if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        }
        if (List.class.isAssignableFrom(type)) {
            return "array";
        }
        if (type.isRecord()) {
            return null;
        }
        throw new AssertionError("this test carries no wire type for " + type.getName()
                + ", newly held by " + component.getDeclaringRecord().getSimpleName() + "."
                + component.getName() + ". Add the mapping deliberately: a monetary value left to"
                + " default to a JSON number rounds beyond fifteen digits.");
    }

    /**
     * Returns whether a declared type satisfies the expected one, allowing a nullable union.
     *
     * @param declared the type the document declares, a name or a union of names
     * @param expected the type the component requires, or {@code null} for a reference
     * @return whether the declaration satisfies the requirement
     */
    private static boolean declares(Object declared, String expected) {
        if (expected == null) {
            return declared == null;
        }
        if (declared instanceof List<?> union) {
            return union.contains(expected);
        }
        return expected.equals(declared);
    }

    /**
     * Returns whether one component is serialized as text rather than as a JSON number.
     *
     * <p>The annotation is looked for on the component, on its accessor and on the backing field,
     * because which of the three carries it depends on how the record declares it.
     *
     * @param component the component to inspect
     * @return whether a to-text serializer is bound to it
     */
    private static boolean sentAsText(RecordComponent component) {
        Class<tools.jackson.databind.annotation.JsonSerialize> annotation =
                tools.jackson.databind.annotation.JsonSerialize.class;
        tools.jackson.databind.annotation.JsonSerialize bound = component.getAnnotation(annotation);
        if (bound == null) {
            bound = component.getAccessor().getAnnotation(annotation);
        }
        if (bound == null) {
            try {
                bound = component.getDeclaringRecord()
                        .getDeclaredField(component.getName()).getAnnotation(annotation);
            } catch (NoSuchFieldException absent) {
                return false;
            }
        }
        return bound != null
                && bound.using() == tools.jackson.databind.ser.std.ToStringSerializer.class;
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

    /**
     * Reads the parameter reference each operation declares, keyed by method and path.
     *
     * <p>Only parameters declared by reference are read. An operation referencing none, or more than
     * one, is reported rather than skipped, so the comparison above cannot pass by omission. A header
     * declared inline is not a reference and is read by {@link #declaredRequestHeaders()} instead.
     *
     * @return the method and path of each operation mapped to its single parameter reference
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> declaredParameterReferences() {
        Map<String, String> references = new TreeMap<>();
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> route : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) route.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                Map<String, Object> definition = (Map<String, Object>) operation.getValue();
                List<Map<String, Object>> declared =
                        (List<Map<String, Object>>) definition.get("parameters");
                String key = operation.getKey().toUpperCase(Locale.ROOT) + " " + route.getKey();
                List<Map<String, Object>> referenced = declared == null ? List.of()
                        : declared.stream().filter(one -> one.containsKey("$ref")).toList();
                if (referenced.size() != 1) {
                    references.put(key, "expected one referenced parameter but found "
                            + referenced.size());
                    continue;
                }
                references.put(key, String.valueOf(referenced.get(0).get("$ref")));
            }
        }
        return references;
    }

    /**
     * Reads whether each operation declares the required first-party request header.
     *
     * @return one entry per operation, true when the header is declared, required and in the header
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Boolean> declaredRequestHeaders() {
        Map<String, Boolean> declaredHeaders = new TreeMap<>();
        Map<String, Object> paths = (Map<String, Object>) openApi.get("paths");
        for (Map.Entry<String, Object> route : paths.entrySet()) {
            Map<String, Object> operations = (Map<String, Object>) route.getValue();
            for (Map.Entry<String, Object> operation : operations.entrySet()) {
                Map<String, Object> definition = (Map<String, Object>) operation.getValue();
                List<Map<String, Object>> declared =
                        (List<Map<String, Object>>) definition.get("parameters");
                boolean present = declared != null && declared.stream().anyMatch(one ->
                        CrossSiteRequestFilter.DEFAULT_REQUIRED_HEADER.equals(one.get("name"))
                                && "header".equals(one.get("in"))
                                && Boolean.TRUE.equals(one.get("required")));
                declaredHeaders.put(
                        operation.getKey().toUpperCase(Locale.ROOT) + " " + route.getKey(), present);
            }
        }
        return declaredHeaders;
    }

}
