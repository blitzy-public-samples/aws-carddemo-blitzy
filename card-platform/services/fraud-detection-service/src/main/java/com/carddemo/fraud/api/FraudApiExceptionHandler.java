package com.carddemo.fraud.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns a failure of a fraud-assessment route into the problem document this service publishes.
 *
 * <p>Every failing path of this service answers one shape, {@code application/problem+json} carrying
 * {@link ApiProblem}. {@code config/SecurityConfig} already writes that shape for {@code 401} and
 * {@code 403}, and {@code src/main/resources/openapi.yaml} declares it for {@code 400} and
 * {@code 500} as well. Without this class those two statuses were answered by the container's error
 * dispatch instead, in {@code application/json} carrying {@code timestamp}, {@code status},
 * {@code error} and {@code path} — a second shape for a client to parse, and one whose {@code path}
 * member copies the resolved request path into the body and into any log built from it. A request
 * target refused before this class can run reaches no handler at all, and
 * {@code config/ContainerErrorDocument} answers that one in the same shape, so the second shape
 * reaches a caller from nowhere.
 *
 * <p>Four kinds of failure reach {@code 400}, and they are all the same client error: a value the
 * route declared a constraint for did not meet it. The framework reports that condition under four
 * different types depending on where the value sat and how it failed, so all four map to
 * {@link ApiProblem#INVALID_REQUEST_CONTENT}. One text for all four is deliberate: a text naming the
 * value would echo an account identifier or a transaction identifier back to the caller.
 *
 * <p>The {@code 400}s that carry their own text are the ones where the answer has something to say:
 * a {@code sort} parameter, whose order is a property of the repository finder and not a choice a
 * caller makes, and a {@code page} parameter, which names a position this route reaches by cursor
 * rather than by counting. {@link FraudAssessmentController.UnsupportedParameterException} holds that
 * text, and it names the parameter and what this route does instead, never a value.
 *
 * <p>A call the protocol refused keeps the status the framework named — {@code 404}, {@code 405},
 * {@code 406} or {@code 415} — together with the headers that status requires, and carries
 * {@link ApiProblem#UNSUPPORTED_REQUEST}. {@code Allow} on a {@code 405} is the header a caller reads
 * to learn which methods a route serves.
 *
 * <p>Any other fault answers {@code 500} carrying {@link ApiProblem#ASSESSMENT_NOT_READ}. The log
 * line names the exception type and the response says nothing, which is the separation this class
 * holds: a message raised inside a driver can quote a value this service stores.
 *
 * <p>The advice deliberately names no base package, and {@code config/ReadinessHealthConfig} is why it
 * does not have to. A poll of {@code /actuator/health} reaches an advice only when a health indicator
 * lets a failure escape, which leaves the actuator with no document to render and sends the request out
 * through the error path. Every indicator that reaches a dependency now catches its own failure and
 * reports that dependency down, so the endpoint renders its own document with {@code 503} and no
 * failure of the management port arrives here at all.
 *
 * <p>Naming a base package was measured and rejected. Spring selects an advice by the type of the
 * handler it resolved, and a request that matches no mapping resolves none: a {@code consumes}
 * condition the request content type misses is the common case. A scoped advice is therefore skipped
 * for exactly the failures raised before a handler is chosen, so the documented answers to an
 * unsupported media type, an unacceptable media type and an unsupported method would each become the
 * framework body an advice exists to replace. Scoping is also neither necessary nor sufficient on its
 * own: this service was scoped throughout and still answered a paused datastore with the framework
 * body, because its indicator threw.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestControllerAdvice
public class FraudApiExceptionHandler {

    /** Records one line per refusal, naming no account and no transaction identifier. */
    private static final Logger log = LoggerFactory.getLogger(FraudApiExceptionHandler.class);
    /** Causes rendered into one failure line before the chain is cut. */
    private static final int FAILURE_TYPE_DEPTH = 3;

    /**
     * Answers a request value that missed the constraint declared for it.
     *
     * <p>Four types reach here. {@link HandlerMethodValidationException} is what the framework
     * raises for a constraint on a handler parameter, which is where every constraint of this
     * service sits. {@link ConstraintViolationException} is the shape a validating proxy reports
     * instead. {@link MethodArgumentTypeMismatchException} covers a page number or page size that is
     * not a whole number at all, which reaches no constraint because the binding fails first. And
     * {@link MissingRequestValueException} covers the required account parameter not arriving.
     *
     * @param failure the reported failure, read for nothing but its type
     * @return {@code 400} carrying the documented refusal text
     */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class, MissingRequestValueException.class})
    public ResponseEntity<ApiProblem> onInvalidRequestValue(Exception failure) {
        log.info("Refusing a fraud-assessment request on {}", failure.getClass().getSimpleName());
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_REQUEST_CONTENT);
    }

    /**
     * Answers a {@code sort} or {@code page} parameter the collection route does not honour.
     *
     * @param failure the refusal, whose text names the parameter and what this route does instead
     * @return {@code 400} carrying that text
     */
    @ExceptionHandler(FraudAssessmentController.UnsupportedParameterException.class)
    public ResponseEntity<ApiProblem> onUnsupportedParameter(
            FraudAssessmentController.UnsupportedParameterException failure) {

        log.info("Refusing a fraud-assessment request naming a parameter this route does not honour");
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.BAD_REQUEST, failure.getMessage());
    }

    /**
     * Answers a paging parameter the collection route could not read as a page.
     *
     * <p>The refusal reaches this arm rather than the status its own annotation names. An
     * {@code @ExceptionHandler} of this advice claims a failure before the framework reads a
     * {@code @ResponseStatus} off it, and the last arm of this class claims every remaining failure
     * with {@code 500}, so a paging refusal needs an arm of its own to answer {@code 400}.
     *
     * @param failure the refusal, whose text names the parameter and the bounds the route reads
     * @return {@code 400} carrying that text
     */
    @ExceptionHandler(FraudAssessmentController.UnreadablePagingValueException.class)
    public ResponseEntity<ApiProblem> onUnreadablePagingValue(
            FraudAssessmentController.UnreadablePagingValueException failure) {

        log.info("Refusing a fraud-assessment request whose paging value could not be read");
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.BAD_REQUEST, failure.getMessage());
    }

    /**
     * Answers a call the protocol refused, at the status and with the headers the framework named.
     *
     * <p>Four failures reach here, each raised before a route ran: a method no route serves, a media
     * type this service does not read, a media type it cannot write, and a path that matches no
     * route. Each carries its own status and its own headers, and {@code Allow} on a {@code 405} is
     * how a caller learns which methods a route does serve. Answering {@code 500} instead would
     * record a caller's mistake as a fault of this service.
     *
     * <p>A {@code 406} carries no body: a caller that accepts no type this service writes cannot be
     * sent a problem document either.
     *
     * @param failure the protocol refusal, read for its status and its headers
     * @return the status the framework named, carrying that status's headers
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeException.class,
            NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiProblem> onUnsupportedRequest(ErrorResponse failure) {
        HttpStatusCode status = failure.getStatusCode();
        HttpStatus resolved = HttpStatus.valueOf(status.value());

        log.info("Refusing a fraud-assessment call on the protocol, answering {}", status.value());
        BodyBuilder response = ResponseEntity.status(status).headers(failure.getHeaders());
        if (resolved == HttpStatus.NOT_ACCEPTABLE) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(resolved.getReasonPhrase(), status.value(),
                        ApiProblem.UNSUPPORTED_REQUEST));
    }

    /**
     * Answers any other fault, and records it.
     *
     * @param failure the fault, logged and otherwise read for nothing but its type
     * @return {@code 500} carrying the documented failure text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> onFault(Exception failure) {
        log.error("A fraud-assessment read failed inside this service. The failure was {}. Its message is not recorded, because a message quotes the value that caused it.", failureType(failure));
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiProblem.INTERNAL_SERVER_ERROR,
                ApiProblem.ASSESSMENT_NOT_READ);
    }

    /**
     * Builds one problem document under the media type RFC 9457 names.
     *
     * @param status the status to answer
     * @param title  the fixed phrase for this class of failure
     * @param detail the fixed explanation
     * @return the response
     */
    private static ResponseEntity<ApiProblem> problem(HttpStatus status, String title,
            String detail) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(title, status.value(), detail));
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
