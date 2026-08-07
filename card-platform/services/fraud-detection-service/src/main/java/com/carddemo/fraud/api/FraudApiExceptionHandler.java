package com.carddemo.fraud.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns a failure of a fraud-assessment route into the problem document this service publishes.
 *
 * <p>Every failing path of this service answers one shape, {@code application/problem+json} carrying
 * {@link ApiProblem}. {@code config/SecurityConfig} already writes that shape for {@code 401} and
 * {@code 403}, and {@code src/main/resources/openapi.yaml} declares it for {@code 400} and
 * {@code 500} as well. Without this class those two statuses were answered by the container's error
 * dispatch instead, in {@code application/json} carrying {@code timestamp}, {@code status},
 * {@code error} and {@code path} — a second shape for a client to parse, and one whose {@code path}
 * member copies the resolved request path into the body and into any log built from it.
 *
 * <p>Four kinds of failure reach {@code 400}, and they are all the same client error: a value the
 * route declared a constraint for did not meet it. The framework reports that condition under four
 * different types depending on where the value sat and how it failed, so all four map to
 * {@link ApiProblem#INVALID_REQUEST_CONTENT}. One text for all four is deliberate: a text naming the
 * value would echo an account identifier or a transaction identifier back to the caller.
 *
 * <p>The one {@code 400} that carries its own text is a {@code sort} parameter, because the answer
 * has something to say — the order is a property of the repository finder and not a choice a caller
 * makes. {@link FraudAssessmentController.UnsupportedSortException} holds that text, and it names the
 * parameter and the order this route applies, never a value.
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
     * Answers a {@code sort} parameter the collection route does not honour.
     *
     * @param failure the refusal, whose text names the parameter and the order this route applies
     * @return {@code 400} carrying that text
     */
    @ExceptionHandler(FraudAssessmentController.UnsupportedSortException.class)
    public ResponseEntity<ApiProblem> onUnsupportedSort(
            FraudAssessmentController.UnsupportedSortException failure) {

        log.info("Refusing a fraud-assessment request naming an order this route does not apply");
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.BAD_REQUEST, failure.getMessage());
    }

    /**
     * Answers any other fault, and records it.
     *
     * @param failure the fault, logged and otherwise read for nothing but its type
     * @return {@code 500} carrying the documented failure text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> onFault(Exception failure) {
        log.error("A fraud-assessment read failed inside this service", failure);
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
}
