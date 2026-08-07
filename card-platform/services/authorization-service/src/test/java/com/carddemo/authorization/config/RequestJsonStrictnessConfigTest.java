package com.carddemo.authorization.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.carddemo.authorization.api.AuthorizationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Asserts the request reader of the web layer holds a caller to the published request schema.
 *
 * <p>Two promises of {@code src/main/resources/openapi.yaml} are under test.
 * {@code AuthorizationRequest} declares {@code additionalProperties: false}, and every property of
 * it declares {@code type: string}. A reader left at its defaults keeps neither: it drops an
 * undeclared property in silence and it turns a JSON number into the text of that number.
 *
 * <p>The mapper is built the way the running service builds it, by
 * {@link JacksonAutoConfiguration} with {@link RequestJsonStrictnessConfig} contributing its
 * customizer. A test that applied the customizer to a builder by hand would pass while the bean was
 * unwired, which is the failure this class exists to catch.
 *
 * <p>Each refusal reaches a caller as {@code 400} carrying one fixed text, which
 * {@code api/GlobalExceptionHandlerTest} covers. Here the assertion is that the read fails at all.
 */
@DisplayName("The web request reader holds a caller to the published request schema")
final class RequestJsonStrictnessConfigTest {

    /** A body every property of which arrives as the schema declares it: text. */
    private static final String WELL_TYPED_BODY = """
            {"transactionTypeCode":"01","transactionCategoryCode":"0001","source":"POS TERM",\
            "description":"Purchase at Abshire-Lowe","amount":"504.77","merchantId":"800000000",\
            "merchantName":"Abshire-Lowe","merchantCity":"North Enoshaven","merchantZip":"72112",\
            "cardNumber":"4859452612877065",\
            "originTimestamp":"2022-06-10 19:27:53.000000",\
            "processingTimestamp":"2022-06-10-19.27.53.000000"}""";

    /** The same body with the amount sent as a JSON number, which the schema does not declare. */
    private static final String NUMERIC_AMOUNT_BODY =
            WELL_TYPED_BODY.replace("\"amount\":\"504.77\"", "\"amount\":504.77");

    /** The same body carrying a property the schema does not declare. */
    private static final String UNDECLARED_PROPERTY_BODY =
            WELL_TYPED_BODY.replace("{", "{\"unknownField\":\"x\",");

    /** The same body with the merchant identifier sent as a JSON integer. */
    private static final String NUMERIC_MERCHANT_ID_BODY =
            WELL_TYPED_BODY.replace("\"merchantId\":\"800000000\"", "\"merchantId\":800000000");

    /** The same body with the active-status style boolean in a text property. */
    private static final String BOOLEAN_SOURCE_BODY =
            WELL_TYPED_BODY.replace("\"source\":\"POS TERM\"", "\"source\":true");

    /** Builds the mapper the running service builds, customizer included. */
    private final ApplicationContextRunner reader = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(RequestJsonStrictnessConfig.class);

    /** Asserts a body typed as the schema declares still binds, so the settings refuse nothing real. */
    @Test
    @DisplayName("A body carrying text in every property binds, and the amount keeps its digits")
    void aWellTypedBodyStillBinds() {
        reader.run(context -> {
            AuthorizationRequest bound = context.getBean(ObjectMapper.class)
                    .readValue(WELL_TYPED_BODY, AuthorizationRequest.class);

            assertNotNull(bound, "a body the schema admits binds");
            assertEquals("504.77", bound.amount(), "the amount arrives as the text it was sent as");
            assertEquals("4859452612877065", bound.cardNumber(),
                    "a card number arrives whole, so the cross-reference read keys on all sixteen");
        });
    }

    /**
     * Asserts a JSON number in the amount is refused rather than read as the text of that number.
     *
     * <p>This is the platform rule that money travels as a decimal string. A reader that coerced the
     * number would bind it through a binary floating-point type, and every arithmetic step of this
     * platform is fixed point with truncation toward zero pinned.
     */
    @Test
    @DisplayName("An amount sent as a JSON number is refused")
    void aNumericAmountIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(NUMERIC_AMOUNT_BODY, AuthorizationRequest.class),
                    "amount is declared as text and a JSON number is not text");
        });
    }

    /** Asserts the rule covers every textual property and not the amount alone. */
    @Test
    @DisplayName("A number or a boolean in any text property is refused")
    void aNumberOrBooleanInAnyTextPropertyIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(NUMERIC_MERCHANT_ID_BODY, AuthorizationRequest.class),
                    "a merchant identifier travels as text, so a leading zero survives");
            assertThrows(JacksonException.class,
                    () -> mapper.readValue(BOOLEAN_SOURCE_BODY, AuthorizationRequest.class),
                    "a boolean is not the text of a capture channel");
        });
    }

    /**
     * Asserts a property the schema does not declare is refused rather than dropped.
     *
     * <p>A caller that misspells {@code cardNumber} would otherwise have its card number ignored,
     * the account branch taken instead, and nothing said about it in the response.
     */
    @Test
    @DisplayName("A property the schema does not declare is refused")
    void anUndeclaredPropertyIsRefused() {
        reader.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);

            assertThrows(JacksonException.class,
                    () -> mapper.readValue(UNDECLARED_PROPERTY_BODY, AuthorizationRequest.class),
                    "the request schema closes its property set");
        });
    }
}
