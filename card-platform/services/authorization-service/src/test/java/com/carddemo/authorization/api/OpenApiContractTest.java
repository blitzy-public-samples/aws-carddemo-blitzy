package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.events.DeclineReason;
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
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Agreement tests between {@code src/main/resources/openapi.yaml} and the records it describes.
 *
 * <p>The description is written by hand, because no documentation generator joins the classpath. That
 * choice buys a readable document and costs a way for the two to drift, so these tests are the
 * mechanism that stops the drift. A field added to a record without a property in the document fails
 * here, and so does the reverse.
 *
 * <p>These tests read one classpath resource. No application context, no database and no broker takes
 * part.
 */
final class OpenApiContractTest {

    /** The hand-written description of this service. */
    private static final String DOCUMENT = "openapi.yaml";

    /** The one path this service serves. */
    private static final String AUTHORIZATIONS_PATH = "/authorizations";

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

    /** Asserts the document describes the one path and the one method this service serves. */
    @Test
    void theDocumentDescribesTheOnePathAndTheOneMethod() {
        assertEquals(Set.of(AUTHORIZATIONS_PATH), paths().keySet(),
                "this service serves one synchronous endpoint");
        assertEquals(Set.of("post"), operation().keySet(),
                "one method carries the authorization call");
    }

    /**
     * Asserts the document describes the six status codes the endpoint answers with.
     *
     * <p>Both outcomes of a decision share {@code 200}, so there is no separate code for a decline.
     * {@code 413} is the request body ceiling {@code config/RequestBodyCeilingFilter} applies.
     */
    @Test
    void theDocumentDescribesTheSixStatusCodes() {
        assertEquals(Set.of("200", "400", "413", "422", "500", "503"), responses().keySet(),
                "an approval answers 200, a decline and a rejected body share 422, and the four "
                        + "remaining failure codes follow");
    }

    /** Asserts the request schema declares one property per record component, and no other. */
    @Test
    void theRequestSchemaMatchesTheRequestRecord() {
        assertEquals(componentNamesOf(AuthorizationRequest.class),
                propertyNamesOf("AuthorizationRequest"),
                "the document describes exactly the components the request record declares");
    }

    /**
     * Asserts the request schema requires the eleven components the source requires, and neither
     * identifier.
     *
     * <p>Neither identifier is listed, because {@code VALIDATE-INPUT-KEY-FIELDS} at
     * {@code app/cbl/COTRN02C.cbl:L195-L230} accepts either one: the account branch at
     * {@code :L196-L209} resolves a card from the alternate index and the card branch at
     * {@code :L210-L223} resolves an account from the cross-reference. A required list can express
     * "this field must arrive" and cannot express "one of these two must arrive", so the either-or
     * rule lives on {@link AuthorizationRequest#isIdentifierSupplied()} and the schema description
     * states it in words. Listing {@code cardNumber} here would document a contract the service does
     * not enforce and would refuse the account branch at the interface.
     */
    @Test
    void theRequestSchemaRequiresTheSourceFieldsAndNeitherIdentifier() {
        assertEquals(new TreeSet<>(List.of("amount", "description", "merchantCity",
                "merchantId", "merchantName", "merchantZip", "originTimestamp",
                "processingTimestamp", "source", "transactionCategoryCode", "transactionTypeCode")),
                new TreeSet<>(requiredOf("AuthorizationRequest")),
                "app/cbl/COTRN02C.cbl:L251-L320 rejects eleven fields when empty, and neither "
                        + "identifier is one of them");
    }

    /**
     * Asserts the document states the either-or rule the required list cannot express.
     *
     * <p>Dropping {@code cardNumber} from the required list is only half of the contract. A reader
     * has to be told that one of the two identifiers must arrive and what happens when neither does,
     * or the document reads as though both were optional.
     */
    @Test
    void theDocumentStatesTheEitherOrIdentifierRule() {
        String request = String.valueOf(schemaOf("AuthorizationRequest").get("description"));
        assertTrue(request.contains("at least one of the two must arrive"),
                "the document states that one identifier is required");
        assertTrue(request.contains(AuthorizationRequest.IDENTIFIER_REQUIRED_MESSAGE),
                "the document quotes the WHEN OTHER refusal a request naming neither receives");
        assertTrue(request.contains(AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE),
                "the document quotes the NOTFND refusal an unresolvable account receives");
        assertFalse(request.contains("not reproduced"),
                "the document must not describe the account branch as omitted");
    }

    /**
     * Asserts the two security-sensitive descriptions agree with the current decision path.
     */
    @Test
    void theDocumentDescribesTheAccountCrossCheckAndTheUnresolvedCardEvent() {
        String accountId = String.valueOf(
                propertyOf("AuthorizationRequest", "accountId").get("description"));
        assertTrue(accountId.contains("Supplied alone, it names the subject"),
                "accountId supplied alone resolves the card the decision runs on");
        assertTrue(accountId.contains("must equal the account"),
                "the documented cross-check refuses a mismatched account");

        String response = String.valueOf(schemaOf("AuthorizationResponse").get("description"));
        assertTrue(response.contains("schema-version-2 TransactionDeclined"),
                "an unresolved card publishes the transaction-keyed decline event");
        assertFalse(response.contains("publishes no event"),
                "the document must not describe the pre-S-11 audit gap");
    }

    /** Asserts the response schema declares one property per record component, and no other. */
    @Test
    void theResponseSchemaMatchesTheResponseRecord() {
        assertEquals(componentNamesOf(AuthorizationResponse.class),
                propertyNamesOf("AuthorizationResponse"),
                "the document describes exactly the components the response record declares");
    }

    /** Asserts the error schema declares one property per record component, and no other. */
    @Test
    void theErrorSchemaMatchesTheErrorRecord() {
        assertEquals(componentNamesOf(ApiErrorResponse.class), propertyNamesOf("ApiErrorResponse"),
                "the document describes exactly the components the error record declares");
    }

    /**
     * Asserts the document enumerates the four reject codes the source assigns, and nothing else.
     *
     * <p>Reject code {@code 0109} is assigned at {@code app/cbl/CBTRN02C.cbl:L556} and inspected
     * nowhere, so it is not a decline and reaches no response.
     */
    @Test
    void theDocumentEnumeratesTheFourRejectCodesAndNoOther() {
        Set<String> documented = new LinkedHashSet<>(enumOf("AuthorizationResponse",
                "declineReasonCode"));
        documented.remove(null);

        Set<String> defined = new LinkedHashSet<>();
        for (DeclineReason reason : DeclineReason.values()) {
            defined.add(reason.code());
        }

        assertEquals(defined, documented,
                "the document enumerates the codes the platform defines and no other");
        assertFalse(documented.contains("0109"),
                "0109 is set at app/cbl/CBTRN02C.cbl:L556 and read nowhere, so it is not a decline");
    }

    /** Asserts the document reproduces the four reject texts character for character. */
    @Test
    void theDocumentReproducesTheFourRejectTextsVerbatim() {
        Set<String> documented = new LinkedHashSet<>(enumOf("AuthorizationResponse",
                "declineReasonDescription"));
        documented.remove(null);

        Set<String> defined = new LinkedHashSet<>();
        for (DeclineReason reason : DeclineReason.values()) {
            defined.add(reason.description());
        }

        assertEquals(defined, documented,
                "each text is the one the source MOVE writes into WS-VALIDATION-FAIL-REASON-DESC");
    }

    /** Asserts the error phrases the document enumerates are the three the error record declares. */
    @Test
    void theDocumentEnumeratesTheThreeErrorPhrases() {
        assertEquals(
                new TreeSet<>(List.of(ApiErrorResponse.VALIDATION_FAILED,
                        ApiErrorResponse.UNPROCESSABLE, ApiErrorResponse.INTERNAL_FAILURE)),
                new TreeSet<>(enumOf("ApiErrorResponse", "error")),
                "the phrase comes from a constant of the record and never from caller text");
    }

    /**
     * Asserts the amount and every identifier travel as text.
     *
     * <p>A JSON number deserializes into a double in most readers, which reintroduces binary floating
     * point into a calculation whose whole correctness argument rests on fixed-point arithmetic.
     */
    @Test
    void theAmountAndEveryIdentifierTravelAsText() {
        for (String property : List.of("amount", "merchantId", "transactionTypeCode",
                "transactionCategoryCode")) {
            assertEquals("string", typeOf("AuthorizationRequest", property),
                    property + " travels as text, so no reader turns it into a double");
        }
        assertTrue(nullableTypeOf("AuthorizationRequest", "accountId").contains("string"),
                "an account identifier travels as text, so a leading zero survives");
        assertTrue(nullableTypeOf("AuthorizationRequest", "cardNumber").contains("string"),
                "a card number travels as text, so a leading zero survives");
    }

    /**
     * Asserts the error schema declares no field that could echo a request value.
     *
     * <p>A framework default error body carries the request path, which puts a card number sent in the
     * wrong position into every log that records the response.
     */
    @Test
    void theErrorSchemaDeclaresNoFieldThatCouldEchoARequestValue() {
        Set<String> declared = propertyNamesOf("ApiErrorResponse");

        for (String forbidden : List.of("path", "uri", "url", "instance", "detail", "trace",
                "exception", "requestId", "query")) {
            assertFalse(declared.contains(forbidden),
                    "the error body carries no " + forbidden + ", so it echoes no request value");
        }
    }

    /** Asserts no example in the document holds a full sixteen-digit card number in a response. */
    @Test
    void noResponseExampleHoldsAFullCardNumber() {
        String responseSection = String.valueOf(responses());

        assertFalse(responseSection.contains("4859452612877065"),
                "no response example carries a Primary Account Number");
    }

    /**
     * Returns the record component names of one record class.
     *
     * @param recordClass the record
     * @return the component names in declaration order
     */
    private static Set<String> componentNamesOf(Class<?> recordClass) {
        return new LinkedHashSet<>(Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList());
    }

    /**
     * Returns the property names one schema of the document declares.
     *
     * @param schemaName the schema
     * @return the property names in document order
     */
    @SuppressWarnings("unchecked")
    private static Set<String> propertyNamesOf(String schemaName) {
        return new LinkedHashSet<>(
                ((Map<String, Object>) schemaOf(schemaName).get("properties")).keySet());
    }

    /**
     * Returns the required property names one schema declares.
     *
     * @param schemaName the schema
     * @return the required names
     */
    @SuppressWarnings("unchecked")
    private static List<String> requiredOf(String schemaName) {
        return (List<String>) schemaOf(schemaName).get("required");
    }

    /**
     * Returns the enumerated values one property declares.
     *
     * @param schemaName the schema
     * @param property   the property
     * @return the enumerated values, which may hold a null entry
     */
    @SuppressWarnings("unchecked")
    private static List<String> enumOf(String schemaName, String property) {
        return new ArrayList<>((List<String>) propertyOf(schemaName, property).get("enum"));
    }

    /**
     * Returns the declared type of one property, for a property declaring a single type.
     *
     * @param schemaName the schema
     * @param property   the property
     * @return the type
     */
    private static String typeOf(String schemaName, String property) {
        return String.valueOf(propertyOf(schemaName, property).get("type"));
    }

    /**
     * Returns the declared types of one property that also accepts null.
     *
     * @param schemaName the schema
     * @param property   the property
     * @return the types as text
     */
    private static String nullableTypeOf(String schemaName, String property) {
        return String.valueOf(propertyOf(schemaName, property).get("type"));
    }

    /**
     * Returns one property of one schema.
     *
     * @param schemaName the schema
     * @param property   the property
     * @return the property node
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> propertyOf(String schemaName, String property) {
        Map<String, Object> properties =
                (Map<String, Object>) schemaOf(schemaName).get("properties");
        Map<String, Object> node = (Map<String, Object>) properties.get(property);
        assertTrue(node != null, schemaName + " declares the property " + property);
        return node;
    }

    /**
     * Returns one schema of the document.
     *
     * @param schemaName the schema
     * @return the schema node
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> schemaOf(String schemaName) {
        Map<String, Object> components = (Map<String, Object>) openApi.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> node = (Map<String, Object>) schemas.get(schemaName);
        assertTrue(node != null, "the document declares the schema " + schemaName);
        return node;
    }

    /**
     * Returns the paths the document declares.
     *
     * @return the path map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paths() {
        return (Map<String, Object>) openApi.get("paths");
    }

    /**
     * Returns the operations the one path declares.
     *
     * @return the operation map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> operation() {
        return (Map<String, Object>) paths().get(AUTHORIZATIONS_PATH);
    }

    /**
     * Returns the responses the one operation declares.
     *
     * @return the response map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> responses() {
        Map<String, Object> post = (Map<String, Object>) operation().get("post");
        return (Map<String, Object>) post.get("responses");
    }
}
