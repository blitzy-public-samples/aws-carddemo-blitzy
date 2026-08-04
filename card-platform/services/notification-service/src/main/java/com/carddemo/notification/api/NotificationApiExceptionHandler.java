package com.carddemo.notification.api;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Turns a failure of the notification endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. The path variable of this service's one route is a card number, so that member would
 * copy a card number into the response body and into any log line built from it. Every response
 * this class returns carries the route template instead, and no value read from the request.
 *
 * <p>Two outcomes. A path variable that misses its pattern answers {@code 400}. Any other fault
 * answers {@code 500} with one fixed text.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md} (planned).
 */
@RestControllerAdvice
public class NotificationApiExceptionHandler {

    /** Text a response carries when the path variable misses its pattern. */
    static final String INVALID_CARD_NUMBER_MESSAGE =
            "Card number must be the masked form: twelve asterisks then the last four digits.";

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
     * Answers any other fault.
     *
     * @param fault the fault, read for nothing but its type
     * @return {@code 500} carrying the route template and one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onFault(Exception fault) {
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
                        INVALID_CARD_NUMBER_MESSAGE,
                        NotificationHistoryController.ROUTE_TEMPLATE));
    }
}
