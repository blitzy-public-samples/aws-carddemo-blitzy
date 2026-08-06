package com.carddemo.notification.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns a failure of the notification endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. The path variable of this service's one route is an account identifier, so that member
 * would copy an identifier into the response body and into any log line built from it. Every
 * response this class returns carries the route template instead, and no value read from the
 * request.
 *
 * <p>Two outcomes. A path variable that misses its pattern answers {@code 400}. Any other fault
 * answers {@code 500} with one fixed text.
 *
 * <p>The fixed text is what the caller reads, and it is deliberately the same text for every fault.
 * That leaves the log as the only place a fault can be diagnosed from, so the {@code 500} branch
 * writes one {@code ERROR} line carrying the fault and its stack trace. Without it a repeatable
 * {@code 500} is invisible on the server: the response says nothing by design, and a reader has no
 * other record that the request was even attempted.
 */
@RestControllerAdvice
public class NotificationApiExceptionHandler {

    /** Writes the one {@code ERROR} line a fault leaves behind. */
    private static final Logger LOGGER =
            LoggerFactory.getLogger(NotificationApiExceptionHandler.class);

    /** Text a response carries when the path variable misses its pattern. */
    static final String INVALID_REQUEST_MESSAGE =
            "Account identifier must be eleven digits, and limit must be at least one.";

    /** Text a response carries when the service faults. */
    static final String SERVICE_FAULT_MESSAGE = "The request could not be completed.";

    /**
     * Answers a path variable that misses its pattern.
     *
     * <p>A class annotated {@code @Validated} validates through a proxy, which reports a violation
     * as this exception.
     *
     * @param violation the reported violation, read for nothing but its type
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException violation) {
        return badRequest();
    }

    /**
     * Answers a path variable that misses its pattern when the framework validates the method
     * itself rather than a proxy.
     *
     * @param violation the reported violation, read for nothing but its type
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onMethodValidation(
            HandlerMethodValidationException violation) {
        return badRequest();
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
     * Builds the one bad-request body.
     *
     * @return {@code 400} carrying the route template and one fixed text
     */
    private static ResponseEntity<ApiErrorResponse> badRequest() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(),
                        INVALID_REQUEST_MESSAGE,
                        NotificationHistoryController.ROUTE_TEMPLATE));
    }
}
