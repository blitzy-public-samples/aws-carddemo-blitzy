package com.carddemo.account.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.CycleCloseRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts the request reader of the web layer holds a caller to the published request schemas.
 *
 * <p>Two promises of {@code src/main/resources/openapi.yaml} are under test.
 * {@code AccountUpdateRequest}, {@code AccountDataRequest}, {@code CustomerDataRequest} and the
 * empty cycle-close body each declare {@code additionalProperties: false}, and every property of
 * the first three declares {@code type: string}. A reader left at its defaults keeps neither: it drops an undeclared property
 * in silence and it turns a JSON number into the text of that number.
 *
 * <p>The mapper is built the way the running service builds it, by
 * {@link JacksonAutoConfiguration} with {@link RequestJsonStrictnessConfig} contributing its
 * customizer. A test that applied the customizer to a builder by hand would pass while the bean was
 * unwired, which is the failure this class exists to catch.
 *
 * <p>Each refusal reaches a caller as {@code 400} carrying one fixed text, which
 * {@code api/AccountApiExceptionHandlerTest} covers. Here the assertion is that the read fails at
 * all.
 */
@DisplayName("The web request reader holds a caller to the published request schemas")
final class RequestJsonStrictnessConfigTest {

    /** The account block, every property of which arrives as the schema declares it: text. */
    private static final String ACCOUNT_BLOCK = """
            {"activeStatus":"Y","currentBalance":"492.00","creditLimit":"6169.00",\
            "cashCreditLimit":"4587.00","openDate":"20110422","expirationDate":"20230309",\
            "reissueDate":"20230309","currentCycleCredit":"0.00","currentCycleDebit":"0.00"}""";

    /** The customer block, in the same all-text form. */
    private static final String CUSTOMER_BLOCK = """
            {"customerId":"000000050","firstName":"Aniya","lastName":"Von",\
            "addressLine1":"1588 Nienow Cape","addressCity":"New Aricchester",\
            "addressStateCode":"OR","addressCountryCode":"USA","addressZip":"97201",\
            "socialSecurityPart1":"931","socialSecurityPart2":"24","socialSecurityPart3":"8469",\
            "eftAccountId":"0074883577","primaryCardHolderIndicator":"Y",\
            "ficoCreditScore":"623"}""";

    /** A body both blocks of which carry text in every property. */
    private static final String WELL_TYPED_BODY =
            "{\"accountData\":" + ACCOUNT_BLOCK + ",\"customerData\":" + CUSTOMER_BLOCK + "}";

    /** The same body with a credit limit sent as a JSON number, which the schema does not declare. */
    private static final String NUMERIC_CREDIT_LIMIT_BODY =
            WELL_TYPED_BODY.replace("\"creditLimit\":\"6169.00\"", "\"creditLimit\":6169.00");

    /** The same body naming the account, which the path names and the record does not declare. */
    private static final String ACCOUNT_IDENTIFIER_IN_BODY = WELL_TYPED_BODY
            .replace("{\"accountData\"", "{\"accountId\":\"00000000050\",\"accountData\"");

    /** The same body carrying a misspelled optional component inside the account block. */
    private static final String MISSPELLED_GROUP_ID_BODY =
            WELL_TYPED_BODY.replace("{\"activeStatus\"", "{\"groupid\":\"\",\"activeStatus\"");

    /** The same body carrying an undeclared property inside the customer block. */
    private static final String UNDECLARED_CUSTOMER_PROPERTY_BODY =
            WELL_TYPED_BODY.replace("{\"customerId\"", "{\"socialSecurityNumber\":\"931248469\","
                    + "\"customerId\"");

    /** The same body with the customer identifier sent as a JSON integer, losing its leading zeros. */
    private static final String NUMERIC_CUSTOMER_ID_BODY =
            WELL_TYPED_BODY.replace("\"customerId\":\"000000050\"", "\"customerId\":50");

    /** The same body with the active-status flag sent as a JSON boolean. */
    private static final String BOOLEAN_ACTIVE_STATUS_BODY =
            WELL_TYPED_BODY.replace("\"activeStatus\":\"Y\"", "\"activeStatus\":true");

    /** The same body with an eight-character date sent as a JSON integer. */
    private static final String NUMERIC_OPEN_DATE_BODY =
            WELL_TYPED_BODY.replace("\"openDate\":\"20110422\"", "\"openDate\":20110422");

    /** Builds the mapper the running service builds, customizer included. */
    private final ApplicationContextRunner reader = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(RequestJsonStrictnessConfig.class);

    /** Asserts a body typed as the schemas declare still binds, so the settings refuse nothing real. */
    @Test
    @DisplayName("A body carrying text in every property binds, and the amounts keep their digits")
    void aWellTypedBodyStillBinds() {
        reader.run(context -> {
            AccountUpdateRequest bound = context.getBean(ObjectMapper.class)
                    .readValue(WELL_TYPED_BODY, AccountUpdateRequest.class);

            assertNotNull(bound, "a body the schemas admit binds");
            assertEquals("6169.00", bound.accountData().creditLimit(),
                    "the limit arrives as the text it was sent as");
            assertEquals("000000050", bound.customerData().customerId(),
                    "and the identifier keeps all nine characters, leading zeros included");
        });
    }

    /**
     * Asserts a JSON number in a monetary component is refused rather than read as its text.
     *
     * <p>This is the platform rule that money travels as a decimal string. A reader that coerced the
     * number would bind it through a binary floating-point type, and every arithmetic step of this
     * platform is fixed point with truncation toward zero pinned.
     */
    @Test
    @DisplayName("A credit limit sent as a JSON number is refused")
    void aNumericAmountIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(NUMERIC_CREDIT_LIMIT_BODY, AccountUpdateRequest.class),
                    "creditLimit is declared as text and a JSON number is not text");
        });
    }

    /** Asserts the rule covers every textual property and not the amounts alone. */
    @Test
    @DisplayName("A number or a boolean in any text property is refused")
    void aNumberOrBooleanInAnyTextPropertyIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(NUMERIC_CUSTOMER_ID_BODY, AccountUpdateRequest.class),
                    "CUST-ID PIC 9(09) is nine characters, and a JSON 50 is two");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(NUMERIC_OPEN_DATE_BODY, AccountUpdateRequest.class),
                    "a date arrives as eight characters, and a JSON number drops a leading zero");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(BOOLEAN_ACTIVE_STATUS_BODY, AccountUpdateRequest.class),
                    "88 FLG-YES-NO-ISVALID at app/cbl/COACTUPC.cbl:L78 lists 'Y' and 'N'");
        });
    }

    /**
     * Asserts a property no schema declares is refused rather than dropped, at either level.
     *
     * <p>{@code AccountUpdateRequest} declares no account identifier: the path names the account
     * being replaced. A body naming one was accepted and ignored, so a caller could name a second
     * account and read a response about the first.
     *
     * <p>{@code groupId} carries no edit, so a misspelling of it was dropped and the column kept its
     * stored value with nothing said about it.
     */
    @Test
    @DisplayName("A property no schema declares is refused, at the outer level and inside a block")
    void anUndeclaredPropertyIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(ACCOUNT_IDENTIFIER_IN_BODY, AccountUpdateRequest.class),
                    "the path is the one place the account is named");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(MISSPELLED_GROUP_ID_BODY, AccountUpdateRequest.class),
                    "a misspelled optional component would otherwise be dropped in silence");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(UNDECLARED_CUSTOMER_PROPERTY_BODY,
                            AccountUpdateRequest.class),
                    "the customer block closes its property set too, and holds no recombined column");
        });
    }

    /**
     * Asserts the cycle-close body admits nothing, which is what its schema declares.
     *
     * <p>{@code CycleCloseRequest} declares no member, and the document publishes the body as an
     * object with {@code additionalProperties: false}. The route read no body at all until this
     * record was bound, so any property was accepted and both billing-cycle accumulators were
     * zeroed for a request that had described something else. An empty object still binds, because
     * an empty object and an absent body describe the same request.
     */
    @Test
    @DisplayName("The cycle-close body admits an empty object and refuses every property")
    void theCycleCloseBodyAdmitsNothingButAnEmptyObject() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertNotNull(mapper.readValue("{}", CycleCloseRequest.class),
                    "an empty object is the body the document publishes as its example");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue("{\"unexpected\":true}", CycleCloseRequest.class),
                    "the operation reads no submitted value, so a property describes a request it "
                            + "does not perform");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue("{\"accountId\":\"00000000050\"}",
                            CycleCloseRequest.class),
                    "the path is the one place the account is named, here as everywhere else");
        });
    }
}
