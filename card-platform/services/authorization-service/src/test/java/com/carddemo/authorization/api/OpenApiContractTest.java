package com.carddemo.authorization.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.authorization.api.GlobalExceptionHandler.ApiErrorResponse;
import com.carddemo.cobol.NumvalParser;
import com.carddemo.events.DeclineReason;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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
 * <p>These tests read one classpath resource, and one of them also reads the checked-in fixture
 * {@code app/data/ASCII/carddata.txt} so it can refuse the card numbers that fixture holds. No
 * application context, no database and no broker takes part.
 */
final class OpenApiContractTest {

    /** The hand-written description of this service. */
    private static final String DOCUMENT = "openapi.yaml";

    /** The one path this service serves. */
    private static final String AUTHORIZATIONS_PATH = "/authorizations";

    /**
     * The document keys that carry structure rather than name a field.
     *
     * <p>{@link #refuseUnmaskedCardValue} walks past these without re-reading whether it sits below a
     * card-named field, so the name of a property survives the {@code example} and {@code value} keys
     * that hold its example.
     */
    private static final Set<String> STRUCTURAL_KEYS = Set.of("content", "schema", "schemas",
            "properties", "example", "examples", "value", "items", "allOf", "oneOf", "anyOf",
            "additionalProperties", "application/json");

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
     * Asserts the document describes the twelve status codes the endpoint answers with.
     *
     * <p>An approval answers {@code 200} and a decline answers {@code 422}, which a rejected body
     * shares, so no code belongs to a decline alone. {@code 401} and {@code 403} are the two answers
     * {@code config/SecurityConfig} writes from the filter chain, and {@code 403} is additionally the
     * caller entitlement refusal {@link GlobalExceptionHandler#onCallerNotEntitled} answers, which is
     * not a decline: no decision was taken and no event was published. {@code 413} is the request body
     * ceiling {@code config/RequestBodyCeilingFilter} applies. {@code 406} and {@code 415} are the
     * two media type refusals {@link GlobalExceptionHandler#onUnsupportedMediaType} carries the
     * framework status of, and {@code 405} is the method refusal
     * {@link GlobalExceptionHandler#onUnsupportedRequest} carries the framework status and the
     * {@code Allow} header of. {@code 429} is the rate ceiling
     * {@code config/RequestRateCeilingFilter} applies, which writes the same problem document the
     * chain writes and names {@code Retry-After}. A caller reads all of them here without opening
     * Java.
     */
    @Test
    void theDocumentDescribesTheTwelveStatusCodes() {
        assertEquals(
                Set.of("200", "400", "401", "403", "405", "406", "413", "415", "422", "429", "500",
                        "503"),
                responses().keySet(),
                "an approval answers 200, a decline and a rejected body share 422, and the nine "
                        + "remaining codes follow");
    }

    /**
     * Asserts the document declares the authentication every operation requires.
     *
     * <p>{@code config/SecurityConfig} authorizes {@code POST /authorizations} for the user role and
     * the administrator role and denies every other route, so no anonymous call reaches a decision. A
     * document declaring no scheme reads as though the endpoint were public.
     */
    @Test
    void theDocumentDeclaresTheBasicSchemeAndRequiresIt() {
        Map<String, Object> schemes = securitySchemes();
        assertEquals(Set.of("basicAuth"), schemes.keySet(),
                "one scheme protects this service");

        Map<String, Object> basic = asMap(schemes.get("basicAuth"));
        assertEquals("http", basic.get("type"), "the scheme is an HTTP authentication scheme");
        assertEquals("basic", basic.get("scheme"), "config/SecurityConfig installs HTTP Basic");
        assertEquals(List.of(Map.of("basicAuth", List.of())), documentSecurity(),
                "the requirement is declared once at the root and covers every operation");

        String description = String.valueOf(basic.get("description"));
        assertTrue(description.contains("ACQUIRER and ADMIN alone"),
                "the scheme states which roles authorize a transaction");
        assertTrue(description.contains("USER is a cardholder identity and is refused here"),
                "the scheme states that a cardholder identity is refused, which is the control "
                        + "config/SecurityConfig applies");
    }

    /**
     * Asserts both statuses the filter chain writes carry the problem document and the challenge.
     *
     * <p>{@code config/SecurityConfig} writes {@code WWW-Authenticate} on a {@code 401} and writes
     * none on a {@code 403}, and both answers carry {@code Cache-Control: no-store} and the media
     * type {@code application/problem+json}. A caller reading only this document has to learn the
     * exact challenge value, because that value is what tells it which scheme to present.
     */
    @Test
    void theTwoSecurityStatusesCarryTheProblemDocumentAndTheChallenge() {
        Map<String, Object> unauthorized = asMap(responses().get("401"));
        Map<String, Object> challenge =
                asMap(asMap(asMap(unauthorized.get("headers")).get("WWW-Authenticate")).get("schema"));
        assertEquals("Basic realm=\"carddemo\", charset=\"UTF-8\"", challenge.get("const"),
                "the challenge is the exact value config/SecurityConfig writes");

        Map<String, Object> forbidden = asMap(responses().get("403"));
        assertFalse(asMap(forbidden.get("headers")).containsKey("WWW-Authenticate"),
                "a 403 follows a credential that was accepted, so it repeats no challenge");

        for (Map<String, Object> answer : List.of(unauthorized, forbidden)) {
            Map<String, Object> content = asMap(answer.get("content"));
            assertEquals("#/components/schemas/Problem",
                    asMap(asMap(content.get("application/problem+json")).get("schema")).get("$ref"),
                    "both answers carry the one problem document schema");
            assertEquals("no-store",
                    asMap(asMap(asMap(answer.get("headers")).get("Cache-Control")).get("schema"))
                            .get("const"),
                    "both answers stay out of every cache");
        }

        assertEquals(Set.of("application/problem+json"),
                asMap(unauthorized.get("content")).keySet(),
                "no handler answers 401, so the chain writes its one media type");
        assertEquals(Set.of("application/problem+json", "application/json"),
                asMap(forbidden.get("content")).keySet(),
                "403 arrives from the chain as a problem document and from "
                        + "GlobalExceptionHandler.onCallerNotEntitled as the service error body, "
                        + "so a caller reads both shapes here");
        assertEquals("#/components/schemas/ApiErrorResponse",
                asMap(asMap(asMap(forbidden.get("content")).get("application/json")).get("schema"))
                        .get("$ref"),
                "the entitlement refusal carries the one error schema every handler writes");
    }

    /**
     * Asserts one schema and one media type describe every body this service writes outside the
     * filter chain.
     *
     * <p>{@code config/RequestBodyCeilingFilter} refuses before the parser runs and once wrote a body
     * of its own shape. It now writes the four members of
     * {@code GlobalExceptionHandler.ApiErrorResponse}, so a caller parses one shape for every refusal
     * a handler or a filter answers with, and the three problem statuses stay the documented
     * exception: {@code config/SecurityConfig} writes two of them and
     * {@code config/RequestRateCeilingFilter} writes the third.
     */
    @Test
    void oneSchemaDescribesEveryBodyWrittenOutsideTheFilterChain() {
        Set<String> problemStatuses = Set.of("401", "403", "429");
        Set<String> decisionStatuses = Set.of("200", "422");

        responses().forEach((status, declared) -> {
            Map<String, Object> answer = asMap(declared);
            if (problemStatuses.contains(status) || decisionStatuses.contains(status)
                    || !answer.containsKey("content")) {
                return;
            }
            Map<String, Object> content = asMap(answer.get("content"));
            assertEquals(Set.of("application/json"), content.keySet(),
                    status + " writes one media type");
            assertEquals("#/components/schemas/ApiErrorResponse",
                    asMap(asMap(content.get("application/json")).get("schema")).get("$ref"),
                    status + " carries the one error schema, and not a shape of its own");
        });
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
     * Asserts the document describes the precedence the source applies to the two identifiers.
     *
     * <p>The {@code EVALUATE TRUE} at {@code app/cbl/COTRN02C.cbl:L195} tests the account field
     * first, and {@code app/cbl/COTRN02C.cbl:L209} moves the card number of the row it read over
     * whatever the card field held. A document describing the account identifier as a cross-check
     * that has to agree with the card would tell a caller the opposite of what the service does.
     */
    @Test
    void theDocumentDescribesTheAccountFirstPrecedence() {
        String accountId = String.valueOf(
                propertyOf("AuthorizationRequest", "accountId").get("description"));
        assertTrue(accountId.contains("A value here decides the call, whether or not cardNumber "
                        + "also arrives"),
                "the account identifier decides the call wherever it arrives");
        assertTrue(accountId.contains("app/cbl/COTRN02C.cbl:L209"),
                "the document cites the MOVE that overwrites the card field");
        assertFalse(accountId.contains("must equal the account"),
                "the document must not describe the account identifier as a cross-check");

        String cardNumber = String.valueOf(
                propertyOf("AuthorizationRequest", "cardNumber").get("description"));
        assertTrue(cardNumber.contains("used only where accountId names none"),
                "the card number is read only on the branch that has no account identifier");
    }

    /**
     * Asserts the document states no clock bound on the capture moment.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L389-L405} validates the date and applies no bound relating it
     * to the current moment, and reject code {@code 0103} at
     * {@code app/cbl/CBTRN02C.cbl:L414-L420} is the whole of the test the platform applies. A
     * document naming a configured window would describe a refusal this service does not answer.
     */
    @Test
    void theDocumentStatesNoClockBoundOnTheCaptureMoment() {
        String originTimestamp = String.valueOf(
                propertyOf("AuthorizationRequest", "originTimestamp").get("description"));
        assertTrue(originTimestamp.contains("No bound relates the value to the clock of this "
                        + "service"),
                "the document states that no clock bound applies");
        assertFalse(originTimestamp.contains("origin-timestamp"),
                "no configuration property bounds the capture moment");
        assertFalse(document().toString().contains("ORIGIN_TIMESTAMP_MAX_AGE_MINUTES"),
                "the document names no environment variable for a window that does not exist");
    }

    /**
     * Asserts the document states where the declared processing moment is used.
     *
     * <p>{@code app/cbl/COTRN02C.cbl:L470} moves {@code TPROCDTI} into {@code TRAN-PROC-TS} on the
     * record the capture program writes. A required property no code reads would refuse a request for
     * a value the service ignores, so the document has to name the column that holds it.
     */
    @Test
    void theDocumentStatesWhereTheDeclaredProcessingMomentIsUsed() {
        String processingTimestamp = String.valueOf(
                propertyOf("AuthorizationRequest", "processingTimestamp").get("description"));
        assertTrue(processingTimestamp.contains("declared_processing_timestamp"),
                "the document names the column the declared value is recorded in");
        assertTrue(processingTimestamp.contains("app/cbl/COTRN02C.cbl:L470"),
                "the document cites the MOVE the column reproduces");
    }

    /**
     * Asserts the response contract names the event an unresolved card publishes.
     */
    @Test
    void theDocumentDescribesTheUnresolvedCardEvent() {
        String declined = String.valueOf(schemaOf("DeclinedAuthorization").get("description"));
        assertTrue(declined.contains("INVALID KEY"),
                "the 0100 branch follows a keyed read that resolved no account");
        assertFalse(declined.contains("publishes no event"),
                "the document must not describe the pre-S-11 audit gap");
    }

    /**
     * Asserts each decision status carries a schema stating the invariants of that outcome.
     *
     * <p>One schema shared by {@code 200} and {@code 422} cannot state that an approval carries no
     * reject code, that a decline carries one, or that the {@code 0100} branch alone names no
     * account. Each of those is an invariant of one status, so each status names its own schema.
     */
    @Test
    void eachDecisionStatusNamesItsOwnSchema() {
        assertEquals("#/components/schemas/ApprovedAuthorization",
                asMap(asMap(asMap(asMap(responses().get("200")).get("content"))
                        .get("application/json")).get("schema")).get("$ref"),
                "200 carries the approval schema");

        List<?> alternatives = (List<?>) asMap(asMap(asMap(asMap(responses().get("422"))
                .get("content")).get("application/json")).get("schema")).get("oneOf");
        assertEquals(List.of("#/components/schemas/DeclinedAuthorization",
                        "#/components/schemas/ApiErrorResponse"),
                alternatives.stream().map(alternative -> asMap(alternative).get("$ref")).toList(),
                "422 carries the decline schema beside the refusal body");

        assertEquals(componentNamesOf(AuthorizationResponse.class),
                Set.copyOf(asList(schemaOf("AuthorizationResponse").get("required"))),
                "every serialized member of a decision is required, so a reader knows the shape");
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

    /**
     * Asserts the error phrases the document enumerates are the four the error record declares.
     *
     * <p>{@code FORBIDDEN} is the fourth, and it is the phrase of the 403 answer a caller reading a
     * subject it may not read receives. It has to be enumerated for the same reason as the other
     * three: a phrase absent from the document is a body a generated client cannot bind, and the
     * document would then describe an answer this service does not give.
     */
    @Test
    void theDocumentEnumeratesTheThreeErrorPhrases() {
        assertEquals(
                new TreeSet<>(List.of(ApiErrorResponse.VALIDATION_FAILED,
                        ApiErrorResponse.UNPROCESSABLE, ApiErrorResponse.FORBIDDEN,
                        ApiErrorResponse.INTERNAL_FAILURE)),
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
     * Asserts the documented amount pattern admits every form the runtime admits.
     *
     * <p>The runtime reads the amount with the grammar of {@code FUNCTION NUMVAL-C}, which
     * {@code app/cbl/COTRN02C.cbl:L383} and {@code app/cbl/COTRN02C.cbl:L456} apply to the same
     * field. A published pattern narrower than that grammar tells a caller a body is invalid while
     * the service accepts it, which is the drift this test exists to stop. Each form below is
     * asserted against the pattern and against
     * {@link com.carddemo.cobol.NumvalParser#isValidNumvalCurrency(String)} together, so neither
     * side can move alone.
     *
     * <p>The forms at the end are refused by both, which keeps the pattern from being widened into
     * meaninglessness: a value carrying two decimal points names no amount, scientific notation is
     * outside the grammar, and the grammar admits one sign and one currency sign, never two of
     * either.
     */
    @Test
    void theDocumentedAmountPatternAdmitsEveryFormTheRuntimeAdmits() {
        String documented = String.valueOf(propertyOf("AuthorizationRequest", "amount")
                .get("pattern"));

        for (String accepted : List.of("504.77", "-125.00", "504.7", "504", "504.777",
                "$1,234.56", "1,234.56", "+00000504.77", "-000000504.77", " 504.77", "504.77 ",
                "504.77-", "125.00CR", "125.00DB", "504.77$", ".77", "504.", "-$504.77",
                "+ 504.77", "504.77 CR", "$ 1,234.56", "999999999.99")) {
            assertTrue(NumvalParser.isValidNumvalCurrency(accepted.strip()),
                    "the runtime reads " + accepted + " through FUNCTION NUMVAL-C");
            assertTrue(accepted.matches(documented),
                    "the published pattern admits " + accepted + ", which the runtime accepts");
        }

        for (String refused : List.of("504.7.7", "1e3", "504,,77", "abc", "", "+504.77-",
                "++504.77", "504.77++", "$504.77$", "$-504.77", "CR504.77", "1,", ",5",
                "+ $ 1,234.56 CR")) {
            assertFalse(NumvalParser.isValidNumvalCurrency(refused.strip()),
                    "the runtime refuses " + refused);
            assertFalse(refused.matches(documented),
                    "the published pattern refuses " + refused + " too");
        }
    }

    /**
     * Asserts the two bounds the pattern cannot state are documented as answered with 422.
     *
     * <p>A regular expression cannot count the digits of a value carrying grouping commas, and it
     * cannot bound a magnitude. Both bounds are real:
     * {@code libs/cobol-compat} {@code NumvalParser} refuses an argument above eighteen digits, and
     * {@link AuthorizationRequest#isAmountAcceptedAndInRange()} refuses a magnitude at or above
     * {@code 1000000000}. A document claiming the pattern is the whole of the rule would tell a
     * caller that a body the service refuses is valid.
     */
    @Test
    void theTwoAmountBoundsThePatternCannotStateAreDocumented() {
        String documented = String.valueOf(propertyOf("AuthorizationRequest", "amount")
                .get("pattern"));
        String description = String.valueOf(propertyOf("AuthorizationRequest", "amount")
                .get("description"));

        assertTrue(description.contains("at most eighteen digits in total"),
                "the document states the digit ceiling the parser applies");
        assertTrue(description.contains("magnitude is below 1000000000"),
                "the document states the magnitude bound the record field imposes");
        assertTrue(description.contains("business constraints answered with 422"),
                "the document names the status a caller receives for either bound");

        for (String beyondABound : List.of("1000000000.00", "000000000000000504.77")) {
            assertTrue(beyondABound.matches(documented),
                    "the pattern admits " + beyondABound + ", which is why the bound is documented");
            assertFalse(requestWithAmount(beyondABound).isAmountAcceptedAndInRange(),
                    "the runtime refuses " + beyondABound);
        }
    }

    /**
     * Asserts every schema-refused text property is refused by the record, and the reverse.
     *
     * <p>Five properties carry free text and each is annotated {@code @NotBlank}, so a value of
     * spaces alone is refused at runtime. A schema admitting one would tell a caller that a body the
     * service refuses is valid, which is the drift this test exists to stop.
     */
    @Test
    void everyFreeTextPropertyRefusesAValueOfSpacesAlone() {
        for (String property : List.of("source", "description", "merchantName", "merchantCity",
                "merchantZip")) {
            Map<String, Object> declared = propertyOf("AuthorizationRequest", property);
            String pattern = String.valueOf(declared.get("pattern"));

            assertEquals(1, declared.get("minLength"),
                    property + " carries at least one character");
            assertFalse("   ".matches(pattern),
                    property + " refuses a value of spaces alone, as @NotBlank does");
            assertFalse("".matches(pattern), property + " refuses an empty value");
            assertTrue("POS TERM".matches(pattern), property + " admits ordinary text");
            assertTrue(" leading".matches(pattern),
                    property + " admits a leading space beside a value");
        }
    }

    /**
     * Asserts the three optional properties admit an explicit JSON null, as the binder does.
     *
     * <p>The canonical constructor of {@link AuthorizationRequest} turns a blank or absent component
     * into {@code null}, so a body sending one of these three as {@code null} binds exactly as a body
     * omitting it does. A schema declaring the property as a string alone would refuse a body the
     * service accepts.
     *
     * <p>The two identifier branches of {@code anyOf} each require a string, so a body whose only
     * identifier is null is refused by the document exactly as
     * {@link AuthorizationRequest#isIdentifierSupplied()} refuses it.
     */
    @Test
    void theOptionalPropertiesAdmitAnExplicitNullExactlyAsTheBinderDoes() {
        assertEquals("null", typeOf("AuthorizationRequest", "transactionId"),
                "no value is accepted for the transaction identifier, and null is not a value");
        assertEquals("[string, null]", nullableTypeOf("AuthorizationRequest", "cardNumber"),
                "the binder reads an absent card number and an explicit null the same way");
        assertEquals("[string, null]", nullableTypeOf("AuthorizationRequest", "accountId"),
                "and the same for the account identifier");
        assertFalse(schemaOf("AuthorizationRequest").containsKey("not"),
                "presence of the transaction identifier is expressed by its type and not by a "
                        + "negation the binder does not apply");

        List<?> branches = asList(schemaOf("AuthorizationRequest").get("anyOf"));
        assertEquals(2, branches.size(), "one branch per identifier");
        for (Object branch : branches) {
            Map<String, Object> declared = asMap(branch);
            String identifier = String.valueOf(asList(declared.get("required")).getFirst());
            assertEquals("string",
                    asMap(asMap(declared.get("properties")).get(identifier)).get("type"),
                    identifier + " has to carry a value on its branch, so a null one is refused");
        }

        assertFalse(new AuthorizationRequest(null, "01", "0001", "POS TERM", "Purchase", "504.77",
                        "800000000", "Merchant", "City", "72112", null, "2022-06-10",
                        "2022-06-10", null).isIdentifierSupplied(),
                "the record refuses a body whose identifiers are both absent");
    }

    /**
     * Name of the one request example published to illustrate a refusal rather than a call.
     *
     * <p>{@code validationFailure} names neither identifier on purpose: it is the body the 422
     * answer below is documented against. It therefore must NOT bind, and asserting that is what
     * keeps it from quietly becoming a valid body while still being described as a refusal.
     */
    private static final String REFUSAL_EXAMPLE = "validationFailure";

    /**
     * Asserts every request example the document publishes is valid against the request schema.
     *
     * <p>An example a caller copies has to bind. The set below is checked against the record itself
     * rather than against the schema text, so an example that names neither identifier, carries a
     * transaction identifier or holds an amount outside the accepted range fails here. The one
     * example published as a refusal, {@value #REFUSAL_EXAMPLE}, is required to fail the identifier
     * test instead.
     */
    @Test
    void everyPublishedRequestExampleBinds() {
        Map<String, Object> examples = asMap(asMap(asMap(asMap(asMap(operation().get("post"))
                .get("requestBody")).get("content")).get("application/json")).get("examples"));

        assertFalse(examples.isEmpty(), "the document publishes at least one request example");

        assertTrue(examples.containsKey(REFUSAL_EXAMPLE),
                "the document publishes the refusal example the 422 answer is documented against");

        examples.forEach((name, declared) -> {
            Map<String, Object> body = asMap(asMap(declared).get("value"));
            AuthorizationRequest request = new AuthorizationRequest(
                    text(body, "transactionId"), text(body, "transactionTypeCode"),
                    text(body, "transactionCategoryCode"), text(body, "source"),
                    text(body, "description"), text(body, "amount"), text(body, "merchantId"),
                    text(body, "merchantName"), text(body, "merchantCity"), text(body, "merchantZip"),
                    text(body, "cardNumber"), text(body, "originTimestamp"),
                    text(body, "processingTimestamp"), text(body, "accountId"));

            if (REFUSAL_EXAMPLE.equals(name)) {
                assertFalse(request.isIdentifierSupplied(),
                        "example " + name + " is published as the body the 422 answer describes, so"
                                + " it has to name neither identifier");
            } else {
                assertTrue(request.isIdentifierSupplied(),
                        "example " + name + " names one identifier, which the schema anyOf requires");
            }
            assertTrue(request.isAmountAcceptedAndInRange(),
                    "example " + name + " holds an amount the runtime reads and stores");
            assertTrue(request.transactionId() == null,
                    "example " + name + " supplies no transaction identifier, which the schema "
                            + "refuses and this service allocates");
        });
    }

    /**
     * Reads one property of an example body as text.
     *
     * @param body     the example body
     * @param property the property to read
     * @return the value, or {@code null} where the example omits the property
     */
    private static String text(Map<String, Object> body, String property) {
        Object value = body.get(property);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Builds a request carrying one amount and valid values everywhere else.
     *
     * @param amount the amount as text
     * @return the request
     */
    private static AuthorizationRequest requestWithAmount(String amount) {
        return new AuthorizationRequest(null, "01", "0001", "POS TERM", "Purchase", amount,
                "800000000", "Merchant", "City", "72112", "0000000000000000", "2022-06-10",
                "2022-06-10", null);
    }

    /**
     * Asserts every rejection text the request record declares is documented, and no reader default
     * is.
     *
     * <p>{@code messages} carries one text per failing field, and the document enumerates the set a
     * caller can read. A constraint added without a message of its own reports the reader's own
     * wording, which names a bound and no field: {@code "size must be between 0 and 100"} tells a
     * caller nothing about which of five text properties it refused. Such a text is absent from the
     * document by construction, so this test fails when one appears.
     */
    @Test
    void everyRejectionTextTheRecordDeclaresIsDocumented() {
        String documentedTexts = String.valueOf(propertyOf("ApiErrorResponse", "messages"));

        for (Field declared : AuthorizationRequest.class.getDeclaredFields()) {
            if (!declared.getName().endsWith("_MESSAGE") || declared.getType() != String.class) {
                continue;
            }
            String text = readText(declared);
            assertTrue(documentedTexts.contains(text),
                    declared.getName() + " carries a text the document does not enumerate: " + text);
        }
    }

    /**
     * Asserts every rejection text the record declares holds a position in the source order.
     *
     * <p>{@code api/GlobalExceptionHandler} publishes the earliest text in source order, and a text
     * absent from {@link AuthorizationRequest#REJECTION_TEXTS_IN_SOURCE_ORDER} sorts after every text
     * that is present. A constraint added without a position would therefore be reported only where it
     * is the sole failure, which is a silent change of the answer a caller reads.
     */
    @Test
    void everyRejectionTextTheRecordDeclaresHoldsAPositionInTheSourceOrder() {
        List<String> declaredTexts = new ArrayList<>();

        for (Field declared : AuthorizationRequest.class.getDeclaredFields()) {
            if (!declared.getName().endsWith("_MESSAGE") || declared.getType() != String.class) {
                continue;
            }
            String text = readText(declared);
            declaredTexts.add(text);
            assertTrue(AuthorizationRequest.REJECTION_TEXTS_IN_SOURCE_ORDER.contains(text),
                    declared.getName() + " holds no position in the source order, so a body failing "
                            + "it and an earlier edit together would report the wrong text");
        }

        assertEquals(declaredTexts.size(),
                AuthorizationRequest.REJECTION_TEXTS_IN_SOURCE_ORDER.size(),
                "the source order names every declared text and nothing else");
        assertEquals(Set.copyOf(declaredTexts),
                Set.copyOf(AuthorizationRequest.REJECTION_TEXTS_IN_SOURCE_ORDER),
                "the source order carries each text once");
    }

    /**
     * Reads one declared text constant of the request record.
     *
     * @param declared the field to read, which the caller has checked is a text constant
     * @return the text the constant holds
     */
    private static String readText(Field declared) {
        try {
            return String.valueOf(declared.get(null));
        } catch (IllegalAccessException unreachable) {
            throw new AssertionError(declared.getName() + " is public and static", unreachable);
        }
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

    /**
     * Asserts no example in this document holds a card number the demo stack would accept.
     *
     * <p>The assertion used to name one card number, which passed the moment that number was
     * removed and said nothing about the other forty-nine. It now reads all fifty numbers from
     * {@code app/data/ASCII/carddata.txt} and refuses every one of them anywhere in the document,
     * request examples and response examples alike. The comparison is a substring test because a
     * fixed-width fixture record has no delimiter: a card number inside one sits at the head of a
     * longer digit run.
     *
     * <p>The second half keeps the rule the retired assertion aimed at: no response example carries
     * an unmasked card number. It refuses a sixteen-bare-digit value under a card-named key rather
     * than any sixteen-digit run, because a blanket rule would be wrong rather than stricter.
     * {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5} makes a transaction identifier
     * sixteen digits too, and the response examples are required to carry those. The request body is
     * deliberately outside that half: a caller sends the full number, exactly as
     * {@code app/cbl/CBTRN02C.cbl:L385} keys its lookup on it.
     *
     * <p>{@code equivalence-tests} {@code CardholderExampleContractTest} holds the same rule across
     * every document of the platform. This one keeps it local to the contract this module ships, so
     * a change here fails this module's own build.
     */
    @Test
    void noExampleHoldsASeededCardNumber() {
        String document = documentText();
        Set<String> seeded = seededCardNumbers();

        assertEquals(50, seeded.size(), "the fixture yields the fifty numbers being refused");
        for (String number : seeded) {
            assertFalse(document.contains(number),
                    "an example carrying a seeded card number is a working value against the demo"
                            + " stack: " + number.substring(0, 6) + "..");
        }

        refuseUnmaskedCardValue(responses(), false, "responses");
    }

    /**
     * Refuses a sixteen-bare-digit value that a card-named key introduces.
     *
     * <p>The card-named flag cannot be read at the leaf, because the example value of a property
     * named {@code cardNumber} sits two keys further down under {@code example}. It cannot simply
     * accumulate down the walk either: the example named {@code declinedUnresolvedCard} contains the
     * word, so an accumulating flag condemns the {@code transactionId} inside it, and a transaction
     * identifier is sixteen digits by {@code TRAN-ID PIC X(16)} at {@code app/cpy/CVTRA05Y.cpy:L5}.
     *
     * <p>So a key that names a field replaces the flag and a key that only carries structure keeps
     * it. {@code cardNumber/example} therefore stays true while
     * {@code declinedUnresolvedCard/value/transactionId} returns to false.
     *
     * @param node the document fragment being walked
     * @param belowCardKey whether the nearest field-naming key names a card
     * @param path the walked path, which names the offending value in the failure message
     */
    private static void refuseUnmaskedCardValue(Object node, boolean belowCardKey, String path) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((key, child) -> {
                String name = String.valueOf(key);
                boolean below = STRUCTURAL_KEYS.contains(name)
                        ? belowCardKey
                        : name.toLowerCase(java.util.Locale.ROOT).contains("card");
                refuseUnmaskedCardValue(child, below, path + "/" + name);
            });
        } else if (node instanceof Iterable<?> items) {
            for (Object item : items) {
                refuseUnmaskedCardValue(item, belowCardKey, path);
            }
        } else if (node != null && belowCardKey) {
            assertFalse(String.valueOf(node).matches("\\d{16}"),
                    "the value at " + path + " is sixteen bare digits, so a response example carries"
                            + " an unmasked card number");
        }
    }

    /**
     * Returns the fifty card numbers {@code app/data/ASCII/carddata.txt} holds.
     *
     * <p>{@code CARD-NUM PIC X(16)} at {@code app/cpy/CVACT02Y.cpy:L5} is the first field of a
     * 150-character record, so the number is the first sixteen characters of each line.
     */
    private static Set<String> seededCardNumbers() {
        Path fixture = repositoryRoot().resolve("app/data/ASCII/carddata.txt");
        try {
            return Files.readAllLines(fixture).stream()
                    .filter(line -> line.length() >= 16)
                    .map(line -> line.substring(0, 16))
                    .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
        } catch (java.io.IOException problem) {
            throw new java.io.UncheckedIOException(problem);
        }
    }

    /**
     * Returns the raw text of the shipped document.
     *
     * <p>Every other test here reads the parsed map. This one reads the text, because a card number
     * can sit in a description or a comment as easily as in an example value and the parsed map
     * drops neither.
     */
    private static String documentText() {
        try (InputStream source =
                OpenApiContractTest.class.getClassLoader().getResourceAsStream(DOCUMENT)) {
            assertTrue(source != null, DOCUMENT + " ships on the classpath of this service");
            return new String(source.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException problem) {
            throw new java.io.UncheckedIOException(problem);
        }
    }

    /** Locates the repository root, which holds {@code app/data}. */
    private static Path repositoryRoot() {
        Path cursor = Path.of("").toAbsolutePath().normalize();
        while (cursor != null) {
            if (Files.isDirectory(cursor.resolve("app/data/ASCII"))) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Cannot locate app/data/ASCII");
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
     * Returns the whole document.
     *
     * @return the parsed document
     */
    private static Map<String, Object> document() {
        return openApi;
    }

    /**
     * Reads one node as a map.
     *
     * @param node a node of the parsed document
     * @return the node as a map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        assertTrue(node instanceof Map, "the node is a mapping");
        return (Map<String, Object>) node;
    }

    /**
     * Reads one node as a list.
     *
     * @param node a node of the parsed document
     * @return the node as a list
     */
    private static List<?> asList(Object node) {
        assertTrue(node instanceof List, "the node is a sequence");
        return (List<?>) node;
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

    /**
     * Returns the security schemes the document declares.
     *
     * @return the scheme map
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> securitySchemes() {
        Map<String, Object> components = (Map<String, Object>) openApi.get("components");
        Map<String, Object> schemes = (Map<String, Object>) components.get("securitySchemes");
        assertTrue(schemes != null, "the document declares the credential a caller presents");
        return schemes;
    }

    /**
     * Returns the document-wide security requirement.
     *
     * @return the requirement list
     */
    @SuppressWarnings("unchecked")
    private static List<Object> documentSecurity() {
        List<Object> requirement = (List<Object>) openApi.get("security");
        assertTrue(requirement != null, "the document states that every operation needs the scheme");
        return requirement;
    }
}
