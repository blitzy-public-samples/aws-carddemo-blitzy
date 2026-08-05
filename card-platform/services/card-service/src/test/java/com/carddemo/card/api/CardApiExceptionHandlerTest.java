package com.carddemo.card.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * Behaviour tests for {@link CardApiExceptionHandler}.
 *
 * <p>What is asserted is that no failure of a card endpoint puts a value read from the request into
 * the response. Three of the four handled kinds arise from a value a caller sent, and one of those
 * values is a full card number, so the body has to carry a text and a route template and nothing
 * else.
 *
 * <p>No application context and no dispatcher takes part. Each handler method is invoked directly with
 * a real exception of the kind it declares.
 */
@DisplayName("the card error body")
class CardApiExceptionHandlerTest {

    /** A full card number, used only to prove no response repeats one. */
    private static final String CARD_NUMBER = "4111111111111150";

    private static ValidatorFactory validatorFactory;

    private CardApiExceptionHandler handler;

    /** Builds the handler before each test. */
    @BeforeEach
    void buildHandler() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        handler = new CardApiExceptionHandler();
    }

    /** Closes the validator factory the test opened. */
    @AfterEach
    void closeValidatorFactory() {
        validatorFactory.close();
    }

    /** A value that missed its constraint. */
    @Nested
    @DisplayName("a value that missed its constraint")
    class ConstraintFailures {

        /** Asserts the constraint text reaches the caller and the status is 400. */
        @Test
        void theConstraintTextReachesTheCaller() {
            ResponseEntity<ApiErrorResponse> response =
                    handler.onConstraintViolation(violationOf(new Constrained("50")));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "a malformed request value answers 400");
            assertNotNull(response.getBody(), "the response carries a body");
            assertEquals(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC,
                    response.getBody().message(),
                    "the text the constraint declares reaches the caller");
            assertEquals(CardController.COLLECTION_ROUTE, response.getBody().route(),
                    "the body carries a route template");
            assertEquals(MediaType.APPLICATION_JSON,
                    response.getHeaders().getContentType(), "the body is JavaScript Object Notation");
        }

        /**
         * Asserts the chosen text is stable when two constraints of one value fail.
         *
         * <p>A validator reports an unordered set, so a body built from its iteration order would
         * differ between runs on the same request. The handler sorts, which makes one request read the
         * same way every time.
         */
        @Test
        void theChosenTextIsStableAcrossRuns() {
            ConstraintViolationException reported = violationOf(new Constrained(""));

            assertTrue(reported.getConstraintViolations().size() > 1,
                    "an absent value fails both constraints of the field");
            String first = handler.onConstraintViolation(reported).getBody().message();
            String second = handler.onConstraintViolation(reported).getBody().message();
            assertEquals(first, second, "the same reported set answers the same way twice");
            assertEquals(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC, first,
                    "the sorted order picks the character-class text of the two");
        }

        /** Asserts a reported set carrying no usable text still answers a body. */
        @Test
        void anEmptyReportedSetStillAnswersABody() {
            ResponseEntity<ApiErrorResponse> response = handler.onConstraintViolation(
                    new ConstraintViolationException("nothing reported", Set.of()));

            assertEquals(CardApiExceptionHandler.MISSING_REQUEST_VALUE_MESSAGE,
                    response.getBody().message(), "a fixed text answers rather than an empty one");
        }
    }

    /** A request value that did not arrive at all. */
    @Nested
    @DisplayName("a request value that did not arrive")
    class MissingValues {

        /** Asserts a missing query parameter answers 400 with the fixed text. */
        @Test
        void aMissingQueryParameterAnswersTheFixedText() {
            ResponseEntity<ApiErrorResponse> response = handler.onMissingRequestValue(
                    new MissingServletRequestParameterException(
                            CardController.ACCOUNT_ID_PARAMETER, "String"));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardApiExceptionHandler.MISSING_REQUEST_VALUE_MESSAGE,
                    response.getBody().message(), "the fixed text names what is required");
        }

        /**
         * Asserts a missing request header is handled by the same method.
         *
         * <p>Both kinds extend one framework type, so one handler answers both. The cursor header is
         * optional, so this case arises only if a future route makes one required.
         */
        @Test
        void aMissingRequestHeaderIsHandledByTheSameMethod() throws Exception {
            ResponseEntity<ApiErrorResponse> response = handler.onMissingRequestValue(
                    new MissingRequestHeaderException(CardController.CURSOR_HEADER,
                            new org.springframework.core.MethodParameter(
                                    CardApiExceptionHandlerTest.class
                                            .getDeclaredMethod("headerTarget", String.class), 0)));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardApiExceptionHandler.MISSING_REQUEST_VALUE_MESSAGE,
                    response.getBody().message(), "one text answers both kinds");
        }
    }

    /** A body that could not be read. */
    @Nested
    @DisplayName("a body that could not be read")
    class UnreadableBody {

        /**
         * Asserts nothing of the unreadable body reaches the response.
         *
         * <p>A parser message names the position it failed at and quotes the characters around it. On
         * this service those characters are a card number, so the body carries a fixed text instead.
         */
        @Test
        void nothingOfTheUnreadableBodyReachesTheResponse() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnreadableBody(
                    new HttpMessageNotReadableException(
                            "Unexpected character at [Source: {\"cardNumber\":\"" + CARD_NUMBER
                                    + "\"}; line: 1]", (org.springframework.http.HttpInputMessage) null));

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardApiExceptionHandler.UNREADABLE_BODY_MESSAGE,
                    response.getBody().message(), "one fixed text answers");
            assertFalse(response.getBody().message().contains(CARD_NUMBER),
                    "the parser message does not reach the response");
            assertFalse(response.getBody().route().contains(CARD_NUMBER),
                    "and neither does the route");
        }
    }

    /** Any other fault. */
    @Nested
    @DisplayName("any other fault")
    class OtherFaults {

        /** Asserts a fault answers 500 with one fixed text and no message of its own. */
        @Test
        void aFaultAnswersFiveHundredWithAFixedText() {
            ResponseEntity<ApiErrorResponse> response = handler.onFault(
                    new IllegalStateException("the card " + CARD_NUMBER + " broke something"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "500");
            assertEquals(CardApiExceptionHandler.SERVICE_FAULT_MESSAGE,
                    response.getBody().message(), "one fixed text answers");
            assertFalse(response.getBody().message().contains(CARD_NUMBER),
                    "the fault message does not reach the response");
        }

        /**
         * Asserts every route this handler reports is a template rather than a resolved path.
         *
         * <p>{@link ApiErrorResponse} refuses a value holding a run of more than four digits, so this
         * assertion would fail at construction were the handler to report a resolved path. Naming it
         * anyway states the property rather than relying on the record to state it.
         */
        @Test
        void everyReportedRouteIsATemplate() {
            for (String route : java.util.List.of(
                    handler.onFault(new IllegalStateException("x")).getBody().route(),
                    handler.onUnreadableBody(new HttpMessageNotReadableException("x",
                            (org.springframework.http.HttpInputMessage) null)).getBody().route(),
                    handler.onMissingRequestValue(new MissingServletRequestParameterException(
                            "accountId", "String")).getBody().route())) {
                assertFalse(route.matches(".*\\d{5,}.*"),
                        "a route holding a run of five digits would be a resolved path: " + route);
                assertTrue(route.startsWith(CardController.BASE_PATH),
                        "every route of this service sits under the card collection: " + route);
            }
        }
    }

    /**
     * A stand-in for one constrained request value, carrying the two constraints the list route
     * declares for its account parameter.
     *
     * @param accountId the value under test
     */
    private record Constrained(
            @NotBlank(message = CardValidationMessages.PROMPT_FOR_ACCT)
            @Pattern(regexp = CardController.ACCOUNT_ID_PATTERN,
                    message = CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC)
            String accountId) {
    }

    /**
     * Reports the violations one stand-in carries, as the framework reports them.
     *
     * @param constrained the stand-in to validate
     * @return the reported exception
     */
    private static ConstraintViolationException violationOf(Constrained constrained) {
        Validator validator = validatorFactory.getValidator();
        Set<ConstraintViolation<Constrained>> violations = validator.validate(constrained);
        assertFalse(violations.isEmpty(), "the stand-in fails at least one constraint");
        return new ConstraintViolationException(violations);
    }

    /**
     * Exists only to give {@link org.springframework.core.MethodParameter} a method to describe.
     *
     * @param cursor the parameter the framework reports as missing
     */
    @SuppressWarnings("unused")
    private void headerTarget(String cursor) {
    }
}
