package com.carddemo.card.api;

import com.carddemo.card.config.ObservabilityConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.domain.CardQueryService;
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
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

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

    /** The registry the handler records infrastructure failures to, fresh for each test. */
    private SimpleMeterRegistry registry;

    /** The counter the handler holds, read to prove which outcomes move it. */
    private Counter infrastructureFailures;

    /** Builds the handler before each test. */
    @BeforeEach
    void buildHandler() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        registry = new SimpleMeterRegistry();
        infrastructureFailures = Counter.builder(ObservabilityConfig.METRIC_CARD_FAILURES)
                .register(registry);
        handler = new CardApiExceptionHandler(infrastructureFailures);
    }

    /** Closes the validator factory the test opened. */
    @AfterEach
    void closeValidatorFactory() {
        validatorFactory.close();
    }

    /**
     * Builds a request the dispatcher has matched to one mapping pattern.
     *
     * <p>The handler reads the pattern from the attribute the dispatcher records, which is what lets a
     * failure of the read route report the read route. A request built without that attribute stands
     * for a failure raised before the dispatcher matched one.
     *
     * @param pattern the mapping pattern the dispatcher matched
     * @return the request
     */
    private static MockHttpServletRequest requestOn(String pattern) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern);
        return request;
    }

    /** @return a request the dispatcher matched to the card collection */
    private static MockHttpServletRequest onTheCollection() {
        return requestOn(CardController.COLLECTION_ROUTE);
    }

    /** @return a request the dispatcher matched to the card-numbered route below the collection */
    private static MockHttpServletRequest onTheCardRoute() {
        return requestOn(CardController.CARD_ROUTE);
    }

    /** A value that missed its constraint. */
    @Nested
    @DisplayName("a value that missed its constraint")
    class ConstraintFailures {

        /** Asserts the constraint text reaches the caller and the status is 400. */
        @Test
        void theConstraintTextReachesTheCaller() {
            ResponseEntity<ApiErrorResponse> response =
                    handler.onConstraintViolation(violationOf(new Constrained("50")),
                            onTheCollection());

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
            String first = handler.onConstraintViolation(reported, onTheCollection())
                    .getBody().message();
            String second = handler.onConstraintViolation(reported, onTheCollection())
                    .getBody().message();
            assertEquals(first, second, "the same reported set answers the same way twice");
            assertEquals(CardValidationMessages.ACCOUNT_FILTER_NOT_NUMERIC, first,
                    "the sorted order picks the character-class text of the two");
        }

        /** Asserts a reported set carrying no usable text still answers a body. */
        @Test
        void anEmptyReportedSetStillAnswersABody() {
            ResponseEntity<ApiErrorResponse> response = handler.onConstraintViolation(
                    new ConstraintViolationException("nothing reported", Set.of()),
                    onTheCollection());

            assertEquals(CardApiExceptionHandler.MISSING_REQUEST_VALUE_MESSAGE,
                    response.getBody().message(), "a fixed text answers rather than an empty one");
        }
    }

    /** A parameter the framework could not convert to the type its route declares. */
    @Nested
    @DisplayName("a parameter that could not be converted")
    class ParameterTypeMismatch {

        /**
         * Asserts a row count that is no number answers 400 rather than 500.
         *
         * <p>The row count is the one request value of this service that is not text, so its
         * conversion runs while the argument is resolved, before any constraint on it. A value of
         * {@code abc} therefore reaches no constraint and no read, and before this handler existed it
         * fell through to the fault handler: a caller read {@code 500} for a request it could have
         * corrected, and the container logged a stack trace at error level for it.
         */
        @Test
        void aRowCountThatIsNoNumberAnswersBadRequest() throws Exception {
            ResponseEntity<ApiErrorResponse> response = handler.onParameterTypeMismatch(
                    typeMismatchOn(CardController.PAGE_SIZE_PARAMETER, "abc"), onTheCollection());

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(),
                    "a value a caller can correct is a bad request and not a fault");
            assertEquals(CardValidationMessages.ADDITIVE_PAGE_SIZE_NOT_A_NUMBER,
                    response.getBody().message(), "the declared text answers");
            assertEquals(CardController.COLLECTION_ROUTE, response.getBody().route(),
                    "the body carries the route template of the list");
            assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType(),
                    "the body is JavaScript Object Notation");
        }

        /** Asserts the submitted value reaches neither member of the response. */
        @Test
        void theSubmittedValueReachesNoMemberOfTheResponse() throws Exception {
            ResponseEntity<ApiErrorResponse> response = handler.onParameterTypeMismatch(
                    typeMismatchOn(CardController.PAGE_SIZE_PARAMETER, CARD_NUMBER),
                    onTheCollection());

            assertFalse(response.getBody().message().contains(CARD_NUMBER),
                    "the value the caller sent does not reach the message");
            assertFalse(response.getBody().route().contains(CARD_NUMBER),
                    "and it does not reach the route");
        }
    }

    /** A paging position or a row count the read cannot browse with. */
    @Nested
    @DisplayName("a paging position or row count the read cannot use")
    class UnusableListRequests {

        /**
         * Asserts a cursor naming no row answers 400 with its declared text.
         *
         * <p>This is the one refusal of the list route no constraint can produce: the cursor has the
         * right shape and resolves to no row, which only a read discovers. Before the read raised a
         * typed refusal it raised a plain {@link IllegalArgumentException}, the fault handler could not
         * tell it from a genuine fault, and a caller paging from a cursor this service no longer holds
         * read {@code 500}.
         */
        @Test
        void aCursorNamingNoRowAnswersBadRequest() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnusableListRequest(
                    new CardQueryService.UnusableListRequest(
                            CardValidationMessages.ADDITIVE_CARD_CURSOR_UNKNOWN),
                    onTheCollection());

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardValidationMessages.ADDITIVE_CARD_CURSOR_UNKNOWN,
                    response.getBody().message(), "the declared text reaches the caller verbatim");
        }

        /** Asserts a row count outside the range answers 400 with its declared text. */
        @Test
        void aRowCountOutsideTheRangeAnswersBadRequest() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnusableListRequest(
                    new CardQueryService.UnusableListRequest(
                            CardValidationMessages.ADDITIVE_PAGE_SIZE_OUT_OF_RANGE),
                    onTheCollection());

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardValidationMessages.ADDITIVE_PAGE_SIZE_OUT_OF_RANGE,
                    response.getBody().message(), "the declared text reaches the caller verbatim");
        }

        /** Asserts a refusal carrying no text still answers a body rather than an empty message. */
        @Test
        void aRefusalCarryingNoTextStillAnswersABody() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnusableListRequest(
                    new CardQueryService.UnusableListRequest("  "), onTheCollection());

            assertEquals(CardApiExceptionHandler.MISSING_REQUEST_VALUE_MESSAGE,
                    response.getBody().message(), "a fixed text answers rather than a blank one");
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
                            CardController.ACCOUNT_ID_PARAMETER, "String"), onTheCollection());

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
                                            .getDeclaredMethod("headerTarget", String.class), 0)),
                    onTheCollection());

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
                                    + "\"}; line: 1]", (org.springframework.http.HttpInputMessage) null),
                    onTheCardRoute());

            assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode(), "400");
            assertEquals(CardApiExceptionHandler.UNREADABLE_BODY_MESSAGE,
                    response.getBody().message(), "one fixed text answers");
            assertFalse(response.getBody().message().contains(CARD_NUMBER),
                    "the parser message does not reach the response");
            assertFalse(response.getBody().route().contains(CARD_NUMBER),
                    "and neither does the route");
        }
    }

    /** A call the protocol refused. */
    @Nested
    @DisplayName("a call the protocol refused")
    class ProtocolRefusals {

        /**
         * Asserts a method a route does not serve answers 405 carrying the Allow header.
         *
         * <p>The catch-all arm claimed this checked exception before the protocol arm existed, so a
         * caller sending a write method to a read route read {@code 500} and the fixed fault text. A
         * client cannot tell that answer from a real fault and may retry a call that cannot succeed.
         * {@code Allow} is the header that names the methods the route does serve.
         */
        @Test
        void aMethodTheRouteDoesNotServeAnswersFourOhFiveWithAllow() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnsupportedRequest(
                    new HttpRequestMethodNotSupportedException("DELETE",
                            java.util.List.of("GET", "PUT")),
                    onTheCollection());

            assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode(),
                    "a method the route does not serve answers 405");
            assertEquals(java.util.List.of("GET", "PUT"), response.getHeaders().getAllow().stream()
                    .map(HttpMethod::name).toList(),
                    "the answer names the methods the route does serve");
            assertEquals(CardApiExceptionHandler.UNSUPPORTED_REQUEST_MESSAGE,
                    response.getBody().message(), "one fixed text answers");
            assertEquals(CardController.COLLECTION_ROUTE, response.getBody().route(),
                    "the body carries a route template");
        }

        /** Asserts a media type the route does not read answers 415 rather than 500. */
        @Test
        void aMediaTypeTheRouteDoesNotReadAnswersFourFifteen() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnsupportedRequest(
                    new org.springframework.web.HttpMediaTypeNotSupportedException(
                            MediaType.TEXT_PLAIN, java.util.List.of(MediaType.APPLICATION_JSON)),
                    onTheCardRoute());

            assertEquals(HttpStatus.UNSUPPORTED_MEDIA_TYPE, response.getStatusCode(),
                    "a media type the route does not read answers 415");
            assertEquals(CardApiExceptionHandler.UNSUPPORTED_REQUEST_MESSAGE,
                    response.getBody().message(), "one fixed text answers");
        }

        /** Asserts a caller accepting nothing this service writes is sent no body. */
        @Test
        void aMediaTypeTheRouteCannotWriteAnswersFourOhSixWithNoBody() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnsupportedRequest(
                    new org.springframework.web.HttpMediaTypeNotAcceptableException(
                            java.util.List.of(MediaType.APPLICATION_JSON)),
                    onTheCollection());

            assertEquals(HttpStatus.NOT_ACCEPTABLE, response.getStatusCode(),
                    "a media type the route cannot write answers 406");
            assertNull(response.getBody(),
                    "a caller accepting nothing this service writes is sent no body");
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
                    new IllegalStateException("the card " + CARD_NUMBER + " broke something"),
                    onTheCollection());

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
                    handler.onFault(new IllegalStateException("x"), onTheCollection())
                            .getBody().route(),
                    handler.onUnreadableBody(new HttpMessageNotReadableException("x",
                            (org.springframework.http.HttpInputMessage) null),
                            onTheCardRoute()).getBody().route(),
                    handler.onMissingRequestValue(new MissingServletRequestParameterException(
                            "accountId", "String"), onTheCollection())
                            .getBody().route())) {
                assertFalse(route.matches(".*\\d{5,}.*"),
                        "a route holding a run of five digits would be a resolved path: " + route);
                assertTrue(route.startsWith(CardController.BASE_PATH),
                        "every route of this service sits under the card collection: " + route);
            }
        }
    }

    /**
     * What moves the infrastructure failure counter, and what deliberately does not.
     *
     * <p>Both handlers below reported their failure in a log line and nowhere else, so nothing an
     * operator watches moved when a read route could not reach the card table. The write path was
     * already counted by {@code domain/CardUpdateService} and never arrives here, because a write that
     * failed while holding the row answers through its own outcome chain. So these two increments cover
     * the reads and cannot double count the writes.
     */
    @Nested
    @DisplayName("the infrastructure failure counter")
    class InfrastructureFailureCounting {

        /** Asserts an unreachable card table is counted and not only logged. */
        @Test
        @DisplayName("rises once when the card table cannot be reached")
        void risesOnceWhenTheCardTableCannotBeReached() {
            handler.onDatastoreUnreachable(
                    new QueryTimeoutException("canceling statement due to statement timeout"),
                    onTheCollection());

            assertEquals(1.0D, infrastructureFailures.count(),
                    "a read that could not reach the card table has to move a series, or an operator "
                            + "watching this service sees a healthy one while every read fails");
        }

        /** Asserts a fault inside the service is counted once. */
        @Test
        @DisplayName("rises once when the service faults")
        void risesOnceWhenTheServiceFaults() {
            handler.onFault(new IllegalStateException("broke"), onTheCollection());

            assertEquals(1.0D, infrastructureFailures.count(), "one fault counts once");
        }

        /**
         * Asserts a refusal leaves the counter alone.
         *
         * <p>Every handler here answers a caller who sent something this service will not serve. A
         * counter that rose on those would report this service as failing whenever a client probed it,
         * which is the reading the separate business and protocol answers exist to prevent.
         */
        @Test
        @DisplayName("stays still for every refusal, whoever caused it")
        void staysStillForEveryRefusal() {
            handler.onUnreadableBody(new HttpMessageNotReadableException("x",
                    (org.springframework.http.HttpInputMessage) null), onTheCardRoute());
            handler.onMissingRequestValue(
                    new MissingServletRequestParameterException("accountId", "String"),
                    onTheCollection());
            handler.onUnusableListRequest(
                    new CardQueryService.UnusableListRequest("page too low"), onTheCollection());

            assertEquals(0.0D, infrastructureFailures.count(),
                    "a refusal is the caller's request and not this service's failure");
        }
    }

    /** The route template the body reports. */
    @Nested
    @DisplayName("the route template the body reports")
    class ReportedRoute {

        /**
         * Asserts a failure of the read route reports the read route.
         *
         * <p>The handler reported the collection template for every failure, so a caller sending an
         * unreadable body to {@code PUT /cards/{cardToken}} was told {@code /cards} had failed. Two
         * routes sit under one collection and a caller cannot tell which one it reached from a body
         * naming the prefix of both. The published example of the member carries the template of the
         * two routes that name one card.
         */
        @Test
        void aFailureOfTheReadRouteReportsTheReadRoute() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("{bad",
                            (org.springframework.http.HttpInputMessage) null),
                    onTheCardRoute());

            assertEquals(CardController.CARD_ROUTE, response.getBody().route(),
                    "the route the dispatcher matched is the route the body names");
        }

        /** Asserts a failure of the collection still reports the collection. */
        @Test
        void aFailureOfTheCollectionReportsTheCollection() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("{bad",
                            (org.springframework.http.HttpInputMessage) null),
                    onTheCollection());

            assertEquals(CardController.COLLECTION_ROUTE, response.getBody().route(),
                    "the list and the update sit on the collection and report it");
        }

        /**
         * Asserts a request the dispatcher matched no pattern on falls back to the collection.
         *
         * <p>A failure raised before the dispatcher matched a pattern carries no attribute to read, and
         * so does a call made outside a request at all. The collection is the prefix of both templates,
         * so it is the answer that claims least.
         */
        @Test
        void anUnmatchedRequestFallsBackToTheCollection() {
            ResponseEntity<ApiErrorResponse> withoutAttribute = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("{bad",
                            (org.springframework.http.HttpInputMessage) null),
                    new MockHttpServletRequest());
            ResponseEntity<ApiErrorResponse> withoutRequest = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("{bad",
                            (org.springframework.http.HttpInputMessage) null),
                    null);

            assertEquals(CardController.COLLECTION_ROUTE, withoutAttribute.getBody().route(),
                    "an unmatched request reports the collection");
            assertEquals(CardController.COLLECTION_ROUTE, withoutRequest.getBody().route(),
                    "and so does a call made outside a request");
        }

        /**
         * Asserts a pattern the response record would refuse falls back rather than throwing.
         *
         * <p>{@link ApiErrorResponse} refuses a run of more than four digits, and an exception thrown
         * inside an exception handler reaches a caller as an unhandled failure. No route of this
         * service carries a path variable, so no pattern can carry such a run today. The fallback keeps
         * a route added later from turning a handled failure into one this class cannot answer.
         */
        @Test
        void aPatternTheRecordWouldRefuseFallsBackRatherThanThrowing() {
            ResponseEntity<ApiErrorResponse> response = handler.onUnreadableBody(
                    new HttpMessageNotReadableException("{bad",
                            (org.springframework.http.HttpInputMessage) null),
                    requestOn("/cards/00000000050"));

            assertEquals(CardController.COLLECTION_ROUTE, response.getBody().route(),
                    "a resolved path is not reported, and it does not throw either");
        }

        /** Asserts the fault answer resolves its route the same way a refusal does. */
        @Test
        void theFaultAnswerResolvesItsRouteTheSameWay() {
            ResponseEntity<ApiErrorResponse> response =
                    handler.onFault(new IllegalStateException("x"), onTheCardRoute());

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(), "500");
            assertEquals(CardController.CARD_ROUTE, response.getBody().route(),
                    "a fault of the read route names the read route");
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

    /**
     * Exists only to give a reported type mismatch a parameter to describe.
     *
     * @param pageSize the parameter whose conversion the framework reports as failed
     */
    @SuppressWarnings("unused")
    private void pageSizeTarget(Integer pageSize) {
    }

    /**
     * Builds the mismatch the framework reports when a query parameter will not convert.
     *
     * @param name  the parameter name
     * @param value the value that would not convert
     * @return the reported exception
     * @throws NoSuchMethodException never, since the described method is declared below
     */
    private static MethodArgumentTypeMismatchException typeMismatchOn(String name, String value)
            throws NoSuchMethodException {
        MethodParameter parameter = new MethodParameter(CardApiExceptionHandlerTest.class
                .getDeclaredMethod("pageSizeTarget", Integer.class), 0);
        return new MethodArgumentTypeMismatchException(value, Integer.class, name, parameter,
                new NumberFormatException("For input string: \"" + value + "\""));
    }

    /**
     * The line written for a fault names the failure type and never the failure's message.
     *
     * <p>The message below is what a real one carries: a constraint name and the value that
     * violated it. Passing the throwable to the logger renders exactly that, which is how a card
     * number reaches a log collector without any code ever formatting one.
     */
    @Test
    @DisplayName("A fault is recorded by type, and its message reaches no log line")
    void aFaultIsRecordedByTypeAndItsMessageReachesNoLogLine() {
        String sentinel = "4111111111111111";
        Exception fault = new IllegalStateException(
                "duplicate key value violates unique constraint pk_card: (card_number)=("
                        + sentinel + ")",
                new IllegalArgumentException("inner " + sentinel));

        java.util.List<ILoggingEvent> lines = recordedLines(() ->
                handler.onFault(fault, new MockHttpServletRequest()));

        assertEquals(1, lines.size(), "one fault writes one line");
        ILoggingEvent line = lines.getFirst();
        assertTrue(line.getFormattedMessage().contains(IllegalStateException.class.getName()),
                "the line names the failure type: " + line.getFormattedMessage());
        assertTrue(line.getFormattedMessage().contains(IllegalArgumentException.class.getName()),
                "the line names the cause type too");
        assertFalse(line.getFormattedMessage().contains(sentinel),
                "the failure message reached the log: " + line.getFormattedMessage());
        assertNull(line.getThrowableProxy(),
                "no throwable is attached, so the appender renders no message and no stack trace");
    }

    /**
     * Runs one call with a recorder attached to the package every service logs under.
     *
     * @param call the call whose log lines are wanted
     * @return every line written during it, in order
     */
    private static java.util.List<ILoggingEvent> recordedLines(Runnable call) {
        ListAppender<ILoggingEvent> recorder = new ListAppender<>();
        recorder.setContext((LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory());
        recorder.start();
        Logger serviceLogger = (Logger) org.slf4j.LoggerFactory.getLogger("com.carddemo");
        serviceLogger.addAppender(recorder);
        try {
            call.run();
        } finally {
            serviceLogger.detachAppender(recorder);
            recorder.stop();
        }
        return java.util.List.copyOf(recorder.list);
    }
}
