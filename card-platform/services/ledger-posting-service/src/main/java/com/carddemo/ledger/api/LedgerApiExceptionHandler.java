package com.carddemo.ledger.api;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns a failure of the balance query into a problem document.
 *
 * <p>This service answered every failure with the framework default body before this class existed,
 * and that body has two problems a caller can see. It carries the resolved request path, so a caller
 * naming an account identifier read that identifier back and copied it into its own access log. And
 * it draws no line between a value the route cannot use, a dependency that is away, and a fault
 * inside this service: a paused datastore answered
 * {@code {"timestamp":...,"status":500,"error":"Internal Server Error","path":"/balances/..."}},
 * which tells an operator nothing about which dependency stopped and invites no retry.
 *
 * <p>Three outcomes now. A path value that misses the eleven-digit shape answers {@code 400}. A
 * datastore this service cannot reach answers {@code 503}, the status the platform answers whenever a
 * dependency rather than a request is at fault, and the detail invites a retry. Anything else answers
 * {@code 500}.
 *
 * <p>The {@code 503} arm names three types and no wider. A connection this service cannot open, a
 * transaction it cannot begin and a statement that ran out of time are all the datastore being away.
 * A constraint violation, a mapping failure or a defect in this service is not, and catching every
 * data-access failure here would answer {@code 503} for a broken query and invite a caller to retry
 * something that cannot succeed.
 *
 * <p>The advice deliberately names no base package, and {@code config/ReadinessHealthConfig} is why it
 * does not have to. A poll of {@code /actuator/health} reaches an advice only when a health indicator
 * lets a failure escape, which leaves the actuator with no document to render and sends the request out
 * through the error path. Every indicator that reaches a dependency catches its own failure and reports
 * that dependency down, so the endpoint renders its own document with {@code 503} and no failure of the
 * management port arrives here at all.
 *
 * <p>Naming a base package was measured and rejected across all six services. Spring selects an advice
 * by the type of the handler it resolved, and a request that matches no mapping resolves none, so a
 * scoped advice is skipped for exactly the failures raised before a handler is chosen and the documented
 * answers to an unsupported media type, an unacceptable media type and an unsupported method each become
 * the framework body an advice exists to replace.
 *
 * <p>A refusal records one INFO line and a fault records one ERROR line. The level is what separates
 * a caller's mistake from this service's, and neither line names the value submitted.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestControllerAdvice
public class LedgerApiExceptionHandler {

    /** Records one line per refusal, naming no account identifier. */
    private static final Logger log = LoggerFactory.getLogger(LedgerApiExceptionHandler.class);

    /**
     * Answers a path value that missed the shape declared for it.
     *
     * <p>Three types reach here. {@link HandlerMethodValidationException} is what the framework
     * raises for a constraint on a handler parameter, which is where the constraint of this route
     * sits. {@link ConstraintViolationException} is the shape a validating proxy reports instead. And
     * {@link MethodArgumentTypeMismatchException} covers a value the binding could not convert, which
     * reaches no constraint because the conversion runs first.
     *
     * @param failure the reported failure, read for nothing but its type
     * @return {@code 400} carrying the documented refusal text
     */
    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiProblem> onInvalidRequestValue(Exception failure) {
        log.info("Refusing a balance query on {}", failure.getClass().getSimpleName());
        return problem(HttpStatus.BAD_REQUEST, ApiProblem.BAD_REQUEST,
                ApiProblem.INVALID_ACCOUNT_ID);
    }

    /**
     * Answers a datastore this service could not reach.
     *
     * <p>The request was well formed and this service holds no defect, so neither {@code 400} nor
     * {@code 500} describes what happened. {@code 503} does, and it is what the projection table
     * being away actually means to a caller: come back.
     *
     * @param failure the reported failure, logged and otherwise read for nothing but its type
     * @return {@code 503} carrying the documented unavailability text
     */
    @ExceptionHandler({DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class, QueryTimeoutException.class})
    public ResponseEntity<ApiProblem> onDatastoreUnreachable(Exception failure) {
        log.error("A balance query could not reach the projection table", failure);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, ApiProblem.SERVICE_UNAVAILABLE,
                ApiProblem.BALANCE_DEPENDENCY_UNAVAILABLE);
    }

    /**
     * Answers any other fault, and records it.
     *
     * @param failure the fault, logged and otherwise read for nothing but its type
     * @return {@code 500} carrying the documented failure text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiProblem> onFault(Exception failure) {
        log.error("A balance query failed inside this service", failure);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ApiProblem.INTERNAL_SERVER_ERROR,
                ApiProblem.BALANCE_NOT_READ);
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
