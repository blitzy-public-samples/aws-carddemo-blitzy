package com.carddemo.card.api;

import com.carddemo.card.api.dto.ApiErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns a failure of a card endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. Two of the three routes of this service name a card in the request body and the list route
 * carries its paging cursor in a request header, so a body that echoed the request would put a card
 * number in the response and in any log line built from it. Every response this class returns
 * carries a route template and one fixed or validated text, and no value read from the request.
 *
 * <p>Four outcomes.
 *
 * <ul>
 * <li>A query parameter or a request header that misses its constraint answers {@code 400} with the
 * text that constraint declares, which is a text of
 * {@code com.carddemo.card.api.dto.CardValidationMessages} for both of the values the list route
 * constrains.</li>
 * <li>A missing required parameter answers {@code 400} with a fixed text. The account the list route
 * lists by is required, and the rule that admits the route reads the same parameter, so a request
 * without it is refused before this class sees it in every deployment that runs the filter chain.
 * This answer exists for the case where it is not.</li>
 * <li>A body that cannot be read at all answers {@code 400} with a fixed text. Nothing of the
 * unreadable body reaches the response: a parser message names the position it failed at and quotes
 * the characters around it, and those characters are a card number.</li>
 * <li>Any other fault answers {@code 500} with one fixed text.</li>
 * </ul>
 *
 * <p>The route template of a failing request is not read from the request. This service has two
 * templates, the collection and the read below it, and a failure of the kinds above can arise on
 * either. The collection template is reported, because it is the prefix of both and because it
 * carries no path variable to resolve. That keeps the promise
 * {@link ApiErrorResponse} makes in its own documentation: the value is a template, and its
 * constructor refuses anything holding a run of more than four digits.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestControllerAdvice
public class CardApiExceptionHandler {

    /** Records one line per refusal, naming no card number and no submitted value. */
    private static final Logger log = LoggerFactory.getLogger(CardApiExceptionHandler.class);

    /** Text a response carries when a required request value did not arrive. */
    static final String MISSING_REQUEST_VALUE_MESSAGE =
            "The account to list cards for is required";

    /** Text a response carries when the request body could not be read as one update. */
    static final String UNREADABLE_BODY_MESSAGE =
            "The request body holds one card in JavaScript Object Notation (JSON) and could not be"
                    + " read as one";

    /** Text a response carries when the service faults. */
    static final String SERVICE_FAULT_MESSAGE = "The request could not be completed";

    /**
     * Answers a query parameter or a request header that misses its constraint.
     *
     * <p>A class annotated {@code @Validated} validates through a proxy, which reports every
     * violation of one call as this one exception. The response carries one text, because
     * {@link ApiErrorResponse} carries one: the source holds one message field,
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, and displays one text at
     * a time.
     *
     * <p>The text is chosen in sorted order rather than in the order the validator reports, so one
     * request reads the same way on every run. Only the list route constrains a value this way, and
     * its three constraints carry three distinct texts.
     *
     * @param violation the reported violations
     * @return {@code 400} carrying the route template and one constraint text
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException violation) {
        log.info("A card request carried a value one constraint refused");
        return badRequest(firstTextOf(violation));
    }

    /**
     * Answers a request value that misses its constraint when the framework validates the method
     * itself rather than a proxy.
     *
     * @param violation the reported violations, read for nothing but their texts
     * @return {@code 400} carrying the route template and one constraint text
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onMethodValidation(
            HandlerMethodValidationException violation) {
        log.info("A card request carried a value one constraint refused");
        TreeSet<String> texts = new TreeSet<>();
        for (ParameterValidationResult result : violation.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String text = error.getDefaultMessage();
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return badRequest(texts.isEmpty() ? MISSING_REQUEST_VALUE_MESSAGE : texts.first());
    }

    /**
     * Answers a required query parameter or request header that did not arrive.
     *
     * @param missing the reported absence, read for nothing but its type
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(MissingRequestValueException.class)
    public ResponseEntity<ApiErrorResponse> onMissingRequestValue(
            MissingRequestValueException missing) {
        log.info("A card request omitted a required request value");
        return badRequest(MISSING_REQUEST_VALUE_MESSAGE);
    }

    /**
     * Answers a request body that could not be read at all.
     *
     * @param unreadable the reported failure, whose message reaches no response body
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> onUnreadableBody(
            HttpMessageNotReadableException unreadable) {
        log.info("A card request carried a body that could not be read");
        return badRequest(UNREADABLE_BODY_MESSAGE);
    }

    /**
     * Answers any other fault.
     *
     * @param fault the fault, whose message reaches no response body
     * @return {@code 500} carrying the route template and one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onFault(Exception fault) {
        log.error("A card request failed inside this service", fault);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        SERVICE_FAULT_MESSAGE, CardController.COLLECTION_ROUTE));
    }

    /**
     * Reads the first constraint text of one reported set, in sorted order.
     *
     * @param violation the reported violations
     * @return the first text, or the fixed missing-value text when the set carries none
     */
    private static String firstTextOf(ConstraintViolationException violation) {
        TreeSet<String> texts = new TreeSet<>();
        if (violation.getConstraintViolations() != null) {
            for (ConstraintViolation<?> reported : violation.getConstraintViolations()) {
                String text = reported.getMessage();
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return texts.isEmpty() ? MISSING_REQUEST_VALUE_MESSAGE : texts.first();
    }

    /**
     * Builds one bad-request body.
     *
     * @param message the one text the response carries
     * @return {@code 400} carrying the route template and that text
     */
    private static ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message,
                        CardController.COLLECTION_ROUTE));
    }
}
