package com.carddemo.account.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns a failure into a status code and a problem document.
 *
 * <p>This replaces the abend path. {@code app/cbl/CBTRN02C.cbl:L707-L711} displays a message, moves
 * {@code 999} into an abend code and calls the language-environment abend service, performing no
 * cleanup; more than twenty call sites reach it. One status code per class of failure replaces that
 * single outcome, so a caller can tell a rejected field from a lost race from a fault.
 *
 * <p>Four classes of failure map to four codes. A body whose fields fail an edit answers {@code 422},
 * because the body was read and its values are not ones the source accepts. A body that cannot be read
 * at all answers {@code 400}. A value this service rejects after binding answers {@code 422} as well,
 * and anything else answers {@code 500}.
 *
 * <p>Two outcomes are deliberately absent from this class, because neither is a failure. A row this
 * service does not hold answers {@code 404} from the controller that looked for it, and an update that
 * lost a race against another writer answers {@code 409} from the controller that ran it. Both are
 * answers the source produces too, at {@code app/cbl/COACTUPC.cbl:L3907-L3915} and
 * {@code app/cbl/COACTUPC.cbl:L3950-L3952}, and neither travels as an exception.
 *
 * <p>No handler below puts a request value into the response. Each body carries the verbatim field
 * texts the source emits plus a status code, and never the rejected value, the request path or a
 * header. A caller that sends a Social Security number in the wrong position reads nothing back that
 * repeats it, and each log line records a count and an exception class rather than a value.
 *
 * <p>Every body is written as {@code application/problem+json}, which is what
 * {@code config/SecurityConfig} already writes for {@code 401} and {@code 403}. One service answering
 * two shapes of error would make a client parse both.
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
public class AccountApiExceptionHandler {

    /** Writes the diagnostic lines this class emits, none carrying a submitted value. */
    private static final Logger LOG = LoggerFactory.getLogger(AccountApiExceptionHandler.class);

    /**
     * Answers {@code 422} for a request body whose fields failed their edits.
     *
     * <p>The messages are the verbatim texts {@code app/cbl/COACTUPC.cbl} moves into
     * {@code WS-RETURN-MSG}. They are sorted and deduplicated, so one body reads the same twice for
     * one request and two callers submitting the same mistakes read the same list.
     *
     * @param failure the validation failure the framework raised
     * @return {@code 422} carrying one text per failing field
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiProblem> onInvalidBody(MethodArgumentNotValidException failure) {
        List<String> messages = distinct(failure.getBindingResult().getAllErrors().stream()
                .map(error -> error instanceof FieldError field ? field.getDefaultMessage()
                        : error.getDefaultMessage())
                .toList());

        LOG.info("Rejecting an account update on {} field edits", messages.size());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                ApiProblem.VALIDATION_FAILED_DETAIL, messages);
    }

    /**
     * Answers {@code 422} for a path variable or a parameter that failed its constraints.
     *
     * <p>An account identifier of the wrong width arrives here rather than through body binding,
     * because it is a method parameter and not a field of a record.
     *
     * @param failure the validation failure the framework raised
     * @return {@code 422} carrying one text per failing constraint
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiProblem> onInvalidParameter(HandlerMethodValidationException failure) {
        List<String> messages = distinct(failure.getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .toList());

        LOG.info("Rejecting an account request on {} parameter constraints", messages.size());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                ApiProblem.VALIDATION_FAILED_DETAIL, messages);
    }

    /**
     * Answers {@code 422} for a constraint failure raised outside request binding.
     *
     * @param failure the constraint failure
     * @return {@code 422} carrying one text per failing constraint
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiProblem> onConstraintViolation(ConstraintViolationException failure) {
        List<String> messages = distinct(failure.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList());

        LOG.info("Rejecting an account request on {} constraints", messages.size());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                ApiProblem.VALIDATION_FAILED_DETAIL, messages);
    }

    /**
     * Answers {@code 400} for a body the reader could not parse.
     *
     * <p>The parser's own message quotes the text it failed on, which can hold a date of birth or a
     * Social Security number, so the body carries a fixed text instead and the log line carries none.
     *
     * @param failure the read failure
     * @return {@code 400} carrying no field text
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiProblem> onUnreadableBody(HttpMessageNotReadableException failure) {
        LOG.info("Rejecting an account update whose body could not be read");
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.MALFORMED_REQUEST,
                ApiProblem.MALFORMED_REQUEST_DETAIL, null);
    }

    /**
     * Answers {@code 422} for a value this service rejects after binding.
     *
     * <p>The response records of this service throw this type when a value and the width its column
     * holds do not agree. The exception message names a component and never repeats a value, so it is
     * safe to log, and the body still carries a fixed text.
     *
     * @param failure the rejected value
     * @return {@code 422} carrying no field text
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiProblem> onRejectedValue(IllegalArgumentException failure) {
        LOG.warn("Rejecting an account request: {}", failure.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, ApiProblem.VALIDATION_FAILED,
                ApiProblem.VALIDATION_FAILED_DETAIL, null);
    }

    /**
     * Answers a call the protocol refused, at the status and with the headers the framework named.
     *
     * <p>Three failures reach here, each raised before a handler ran: a method the route does not
     * serve, a media type this service cannot write, and a path that matches no route. Each carries
     * its own status and its own headers, and {@code Allow} on a {@code 405} names the methods the
     * route does serve. Answering these here keeps every failure of this service in one document
     * shape. A request target refused before this class could run — one the security firewall or
     * Tomcat itself rejects — is answered in the same shape by
     * {@code config/ContainerErrorDocument}, so no framework body and no HTML page reaches a
     * caller of this service.
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

        LOG.info("Refusing an account call on the protocol, answering {}", status.value());
        BodyBuilder response = ResponseEntity.status(status).headers(failure.getHeaders());
        if (resolved == HttpStatus.NOT_ACCEPTABLE) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(ApiProblem.of(status.value(), resolved.getReasonPhrase(),
                        ApiProblem.UNSUPPORTED_REQUEST_DETAIL));
    }

    /**
     * Answers {@code 500} for any other fault.
     *
     * <p>The exception class reaches the log and the exception message does not, because a message
     * raised deep in a driver or a parser can quote a value this service holds.
     *
     * @param failure the fault
     * @return {@code 500} carrying no field text
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiProblem> onInternalFailure(RuntimeException failure) {
        LOG.error("An account call failed with {}", failure.getClass().getName());
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiProblem.INTERNAL_FAILURE,
                ApiProblem.INTERNAL_FAILURE_DETAIL, null);
    }

    /**
     * Sorts and deduplicates the texts one failure produced, dropping any that carries nothing.
     *
     * <p>A framework can report the same text twice when two annotations on one component fail
     * together, and a caller reading the same sentence twice learns nothing the first reading did not
     * already give it.
     *
     * @param texts the texts the failure carried, any of which may be {@code null} or blank
     * @return the distinct texts in order, or the single title when the failure carried none
     */
    private static List<String> distinct(List<String> texts) {
        List<String> distinct = new ArrayList<>(new TreeSet<>(texts.stream()
                .filter(text -> text != null && !text.isBlank())
                .toList()));
        return distinct.isEmpty() ? List.of(ApiProblem.VALIDATION_FAILED) : distinct;
    }

    /**
     * Builds one response carrying a problem document.
     *
     * @param status   the status to answer with
     * @param title    one of the titles {@link ApiProblem} declares
     * @param detail   one of the details {@link ApiProblem} declares
     * @param messages one text per failing field, or {@code null} when the failure produced none
     * @return the response, typed {@code application/problem+json}
     */
    private static ResponseEntity<ApiProblem> problem(HttpStatus status, String title, String detail,
            List<String> messages) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(messages == null ? ApiProblem.of(status.value(), title, detail)
                        : ApiProblem.of(status.value(), title, detail, messages));
    }
}
