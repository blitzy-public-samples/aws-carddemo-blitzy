package com.carddemo.notification.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns a failure of the notification endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. The path variable of this service's one route is a card number, so that member would copy a
 * full Primary Account Number into the response body and into any log line built from it. Every
 * response this class returns carries the route template instead, and no value read from the request.
 *
 * <p>Two outcomes. A request value the route cannot use answers {@code 400}, carrying the text of the
 * constraint that refused it. Any other fault answers {@code 500} with one fixed text.
 *
 * <p>One failure reaches the {@code 400}: the path variable misses its shape. The route reads that one
 * value and converts nothing, so no conversion failure arises ahead of the constraints.
 *
 * <p>A refusal records one INFO line and no more. It names neither the value submitted nor the
 * constraint. The response already tells the caller which value to change, and a line naming the value
 * would put a card number in the log this class exists to keep one out of. The level is what carries
 * the meaning: a caller's mistake is traffic rather than a fault of this service.
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
    /** Causes rendered into one failure line before the chain is cut. */
    private static final int FAILURE_TYPE_DEPTH = 3;

    /**
     * Text a response carries when a request value carried no text of its own.
     *
     * <p>Every constraint of {@link NotificationHistoryController} declares its own message, so this
     * text is reached only if a future constraint arrives without one. It names the one value the
     * route reads and nothing a caller submitted.
     */
    static final String INVALID_REQUEST_MESSAGE =
            "Card number must be a value this route admits";

    /** Text a response carries when the service faults. */
    static final String SERVICE_FAULT_MESSAGE = "The request could not be completed.";

    /**
     * Text a response carries when the protocol refused the call.
     *
     * <p>A method this route does not serve, a media type it does not read, a media type it cannot
     * write and a path that matches no route all carry this text. The status separates them, and a
     * {@code 405} also carries {@code Allow}. The text names neither the method nor the path
     * submitted.
     */
    static final String UNSUPPORTED_REQUEST_MESSAGE =
            "This route does not serve the method, path or media type the request named.";

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
     * Answers a call the protocol refused, at the status and with the headers the framework named.
     *
     * <p>Four failures reach here, each raised before the route ran: a method this route does not
     * serve, a media type it does not read, a media type it cannot write, and a path that matches no
     * route. Each carries its own status and its own headers, and {@code Allow} on a {@code 405} is
     * how a caller learns that the route serves {@code GET} alone. Answering {@code 500} instead
     * would record a caller's mistake as a fault of this service and invite an unsafe retry.
     *
     * <p>The body carries the route template, as every body this class writes does, so no resolved
     * path and therefore no card number reaches a caller through a refusal. A {@code 406} carries no
     * body at all: a caller that accepts no type this route writes cannot be sent one.
     *
     * @param failure the protocol refusal, read for its status and its headers
     * @return the status the framework named, carrying that status's headers
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeException.class,
            NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> onUnsupportedRequest(ErrorResponse failure) {
        HttpStatusCode status = failure.getStatusCode();
        HttpStatus resolved = HttpStatus.valueOf(status.value());

        LOGGER.info("Refusing a notification call on the protocol, answering {}", status.value());
        BodyBuilder response = ResponseEntity.status(status).headers(failure.getHeaders());
        if (resolved == HttpStatus.NOT_ACCEPTABLE) {
            return response.build();
        }
        return response.body(new ApiErrorResponse(status.value(), UNSUPPORTED_REQUEST_MESSAGE,
                NotificationHistoryController.ROUTE_TEMPLATE));
    }

    /**
     * Answers any other fault, and records it.
     *
     * <p>The response body carries the route template and one fixed text, so it names neither the
     * fault nor any value read from the request. The {@code ERROR} line names the route by its
     * template, the status, and the type of the fault with the types of its causes. It does not
     * carry the fault itself: a stack trace renders the exception message, and a message quotes the
     * value that caused the failure, which is how a constraint violation, a query timeout or a
     * broken connection puts a row value, a statement or a data-source URL into an ordinary log.
     * A reader therefore learns which failure class occurred and the caller learns nothing from the
     * response, which is the separation this class exists to hold.</p>
     *
     * @param fault the fault, logged and otherwise read for nothing but its type
     * @return {@code 500} carrying the route template and one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onFault(Exception fault) {
        LOGGER.error("The route {} answered {} because a fault reached the handler. The failure"
                        + " was {}. Its message is not recorded, because a message quotes the value"
                        + " that caused it.",
                NotificationHistoryController.ROUTE_TEMPLATE,
                HttpStatus.INTERNAL_SERVER_ERROR.value(), failureType(fault));

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
     * <p>The results arrive in the order the method declares its parameters, and the route declares
     * one. Reading them in declaration order keeps that first-error-wins ordering the same on every
     * run.
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

    /**
     * Renders one failure as its type and the types of its causes, and never as its message.
     *
     * <p>A type is code and safe to record. An exception message is not: a constraint violation
     * quotes the value that violated it, a query timeout quotes the statement, and a connection
     * failure quotes the data-source URL. Passing the throwable to the logger emits both, so this
     * method emits the half that is code and drops the half that is data.
     *
     * <p>The chain is bounded because a wrapped failure can nest deeply and one log line is not the
     * place to render all of it. Three levels reach the framework wrapper, the driver exception and
     * the cause underneath it, which is what a reader needs to tell a timeout from a constraint from
     * a broken connection.
     *
     * @param failure the failure that reached this handler
     * @return the type chain as text, never null and never a message
     */
    private static String failureType(Throwable failure) {
        StringBuilder types = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < FAILURE_TYPE_DEPTH; depth++) {
            if (depth > 0) {
                types.append(" caused by ");
            }
            types.append(current.getClass().getName());
            current = current.getCause() == current ? null : current.getCause();
        }
        return types.toString();
    }
}
