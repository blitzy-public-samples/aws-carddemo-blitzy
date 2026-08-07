package com.carddemo.notification.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns a failure of the notification endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. The path variable of this service's one route is a card token, so that member would copy
 * an identifier of one cardholder's card into the response body and into any log line built from it.
 * Every response this class returns carries the route template instead, and no value read from the
 * request.
 *
 * <p>Two outcomes. A request value the route cannot use answers {@code 400}, carrying the text of
 * the constraint that refused it so a caller reads which of the two values was wrong. Any other
 * fault answers {@code 500} with one fixed text.
 *
 * <p>Three failures reach the {@code 400}. The path variable misses its shape, the page size falls
 * below its floor, and the page size is no whole number at all. The third arrives as a conversion
 * failure rather than as a constraint violation, because a query string is text and the page size is
 * the one value of this route that is not: it converts before any constraint runs, so a conversion
 * that fails reaches none. Without an arm of its own it fell to the {@code 500} below, which
 * answered a fault for a value a caller had simply mistyped.
 *
 * <p>A refusal records one INFO line and no more. It names neither the value submitted nor the
 * constraint, because the response already tells the caller which value to change and a line naming
 * the value would put a card token in the log this class exists to keep one out of. The level is what
 * carries the meaning: a caller's mistake is traffic, so a mistyped page size leaves an INFO line
 * where it used to leave an ERROR line with a stack trace.
 *
 * <p>The fixed text is what the caller reads, and it is deliberately the same text for every fault.
 * That leaves the log as the only place a fault can be diagnosed from, so the {@code 500} branch
 * writes one {@code ERROR} line carrying the fault and its stack trace. Without it a repeatable
 * {@code 500} is invisible on the server: the response says nothing by design, and a reader has no
 * other record that the request was even attempted.
 *
 * <p>The advice deliberately names no base package, and {@code config/ReadinessHealthConfig} is why it
 * does not have to. A poll of {@code /actuator/health} reached this class only because a health
 * indicator let a failure escape, which left the actuator with no document to render and sent the
 * request out through the error path. Every indicator that reaches a dependency now catches its own
 * failure and reports that dependency down, so the endpoint renders its own document with {@code 503}
 * and no failure of the management port arrives here at all.
 *
 * <p>Naming a base package was measured and rejected. Spring selects an advice by the type of the
 * handler it resolved, and a request that matches no mapping resolves none: a {@code consumes}
 * condition that the request content type misses is the common case. A scoped advice is therefore
 * skipped for exactly the failures raised before a handler is chosen, and the documented answers to an
 * unsupported media type, an unacceptable media type and an unsupported method would each become the
 * framework body this class exists to replace. Scoping is also neither necessary nor sufficient on its
 * own: {@code fraud-detection-service} was scoped throughout and still answered a paused datastore
 * with the framework body, because its indicator threw.
 */
@RestControllerAdvice
public class NotificationApiExceptionHandler {

    /** Writes the one {@code ERROR} line a fault leaves behind. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationApiExceptionHandler.class);

    /**
     * Text a response carries when a request value carried no text of its own.
     *
     * <p>Every constraint of {@link NotificationHistoryController} declares its own message, so this
     * text is reached only if a future constraint arrives without one. It names the two values the
     * route reads and nothing a caller submitted.
     */
    static final String INVALID_REQUEST_MESSAGE =
            "Card token and page size must each be a value this route admits";

    /** Text a response carries when the service faults. */
    static final String SERVICE_FAULT_MESSAGE = "The request could not be completed.";

    /**
     * Answers a request value one constraint refused.
     *
     * <p>A class annotated {@code @Validated} validates through a proxy, which reports a violation
     * as this exception.
     *
     * @param violation the reported violation, read for the text of the constraint that refused
     * @return {@code 400} carrying the route template and that constraint's text
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException violation) {
        LOGGER.info("A notification request carried a value one constraint refused");
        return badRequest(firstTextOf(violation));
    }

    /**
     * Answers a request value one constraint refused when the framework validates the method itself
     * rather than a proxy.
     *
     * @param violation the reported violation, read for the text of the constraint that refused
     * @return {@code 400} carrying the route template and that constraint's text
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onMethodValidation(
            HandlerMethodValidationException violation) {
        LOGGER.info("A notification request carried a value one constraint refused");
        return badRequest(firstTextOf(violation));
    }

    /**
     * Answers a page size the framework could not read as a whole number.
     *
     * <p>The conversion runs ahead of every constraint, so this failure reaches no constraint and
     * carries no text of its own. {@code abc}, {@code 7.5} and a value wider than the type all arrive
     * here.
     *
     * @param mismatch the reported conversion failure, read for nothing but its type
     * @return {@code 400} carrying the route template and the page-size text
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> onParameterTypeMismatch(
            MethodArgumentTypeMismatchException mismatch) {
        LOGGER.info("A notification request carried a page size that is no whole number");
        return badRequest(NotificationHistoryController.PAGE_SIZE_NOT_A_NUMBER_MESSAGE);
    }

    /**
     * Answers any other fault, and records it.
     *
     * <p>The response body carries the route template and one fixed text, so it names neither the
     * fault nor any value read from the request. The {@code ERROR} line carries the fault itself,
     * with its stack trace, and names the route by its template rather than by the resolved path.
     * A reader therefore learns what failed from the log and the caller learns nothing from the
     * response, which is the separation this class exists to hold.</p>
     *
     * @param fault the fault, logged and otherwise read for nothing but its type
     * @return {@code 500} carrying the route template and one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onFault(Exception fault) {
        LOGGER.error("The route {} answered {} because a fault reached the handler. The response"
                        + " carries one fixed text, so this line is the only record of what"
                        + " failed.",
                NotificationHistoryController.ROUTE_TEMPLATE,
                HttpStatus.INTERNAL_SERVER_ERROR.value(), fault);

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        SERVICE_FAULT_MESSAGE, NotificationHistoryController.ROUTE_TEMPLATE));
    }

    /**
     * Reports the text of the first constraint a proxy validation refused on.
     *
     * @param violation the reported violation
     * @return that constraint's text, or {@link #INVALID_REQUEST_MESSAGE} when it declared none
     */
    private static String firstTextOf(ConstraintViolationException violation) {
        for (ConstraintViolation<?> refused : violation.getConstraintViolations()) {
            String text = refused.getMessage();
            if (text != null && !text.isBlank()) {
                return text;
            }
        }
        return INVALID_REQUEST_MESSAGE;
    }

    /**
     * Reports the text of the first constraint a method validation refused on.
     *
     * <p>The results arrive in the order the method declares its parameters, so a request wrong in
     * both values reads the card-token text: the path variable is declared first, and a route whose
     * path variable names no card has nothing to page through. Reading them in declaration order
     * keeps that first-error-wins ordering the same on every run.
     *
     * @param violation the reported violation
     * @return that constraint's text, or {@link #INVALID_REQUEST_MESSAGE} when none declared one
     */
    private static String firstTextOf(HandlerMethodValidationException violation) {
        for (ParameterValidationResult result : violation.getParameterValidationResults()) {
            for (MessageSourceResolvable refused : result.getResolvableErrors()) {
                String text = refused.getDefaultMessage();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return INVALID_REQUEST_MESSAGE;
    }

    /**
     * Builds a bad-request body around the text the refusal carried.
     *
     * @param message the text of the constraint that refused
     * @return {@code 400} carrying the route template and that text
     */
    private static ResponseEntity<ApiErrorResponse> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message,
                        NotificationHistoryController.ROUTE_TEMPLATE));
    }
}
