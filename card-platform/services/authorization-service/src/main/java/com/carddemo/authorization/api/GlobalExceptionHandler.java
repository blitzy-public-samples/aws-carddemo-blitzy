package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.AuthorizationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns a failure into a status code and a sanitized body.
 *
 * <p>This replaces the abend path. {@code app/cbl/CBTRN02C.cbl:L707-L711} displays a message, moves
 * {@code 999} into an abend code and calls the language-environment abend service, performing no
 * cleanup, and more than twenty call sites reach it. One status code per failure class replaces that
 * single outcome.
 *
 * <p>Three classes of failure map to three codes. A request whose fields fail validation answers
 * {@code 422}, because the body was read but its values are not ones the source accepts. A request
 * whose body cannot be read at all answers {@code 400}. Anything else answers {@code 500}.
 *
 * <p>A decline is none of these. {@link AuthorizationController} answers {@code 422} for one, from a
 * value the decision service returned, so no handler below ever sees a decline.
 *
 * <p>No handler below puts a request value into the response. The body carries the verbatim field
 * texts the source emits plus a status code, and never the rejected value, the request path or a
 * header. A caller that sends a card number in the wrong position therefore reads nothing back that
 * repeats it, and the log line records a count and an exception class rather than a value.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The text a body that cannot be read reports, in place of the parser's own message. */
    static final String UNREADABLE_BODY_MESSAGE =
            "Request body holds one authorization in JavaScript Object Notation...";

    /** The text an internal fault reports, in place of the exception's own message. */
    static final String INTERNAL_FAILURE_MESSAGE =
            "Unable to authorize this transaction...";

    /**
     * Answers {@code 422} for a request body whose fields failed validation.
     *
     * <p>The messages are the verbatim texts {@code app/cbl/COTRN02C.cbl} moves into
     * {@code WS-MESSAGE}. They are sorted and deduplicated, so one body reads the same twice for one
     * request.
     *
     * @param failure the validation failure the framework raised
     * @return {@code 422} carrying one text per failing field
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidBody(MethodArgumentNotValidException failure) {
        List<String> messages = new ArrayList<>(new TreeSet<>(
                failure.getBindingResult().getAllErrors().stream()
                        .map(error -> error instanceof FieldError field
                                ? field.getDefaultMessage()
                                : error.getDefaultMessage())
                        .filter(message -> message != null && !message.isBlank())
                        .toList()));
        if (messages.isEmpty()) {
            messages = List.of(ApiErrorResponse.VALIDATION_FAILED);
        }

        log.info("Rejecting an authorization request on {} field validations", messages.size());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of(HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        ApiErrorResponse.VALIDATION_FAILED, messages));
    }

    /**
     * Answers {@code 422} for a constraint failure raised outside request-body binding.
     *
     * @param failure the constraint failure
     * @return {@code 422} carrying one text per failing constraint
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException failure) {
        List<String> messages = new ArrayList<>(new TreeSet<>(failure.getConstraintViolations()
                .stream()
                .map(ConstraintViolation::getMessage)
                .filter(message -> message != null && !message.isBlank())
                .toList()));
        if (messages.isEmpty()) {
            messages = List.of(ApiErrorResponse.VALIDATION_FAILED);
        }

        log.info("Rejecting an authorization request on {} constraints", messages.size());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of(HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        ApiErrorResponse.VALIDATION_FAILED, messages));
    }

    /**
     * Answers {@code 400} for a body the reader could not parse.
     *
     * <p>The parser's own message quotes the text it failed on, which can hold a card number, so the
     * body carries a fixed text instead and the log line carries a length.
     *
     * @param failure the read failure
     * @return {@code 400} carrying one fixed text
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> onUnreadableBody(
            HttpMessageNotReadableException failure) {
        log.info("Rejecting an authorization request whose body could not be read");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiErrorResponse.of(HttpStatus.BAD_REQUEST.value(),
                        ApiErrorResponse.UNPROCESSABLE, UNREADABLE_BODY_MESSAGE));
    }

    /**
     * Answers {@code 422} for a value this service rejects after binding.
     *
     * <p>The response records of this service throw this type when an outcome and its identity do not
     * agree. The exception message names a component and never repeats a value, so it is safe to log,
     * and the body still carries a fixed text.
     *
     * @param failure the rejected value
     * @return {@code 422} carrying one fixed text
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> onRejectedValue(IllegalArgumentException failure) {
        log.warn("Rejecting an authorization request: {}", failure.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of(HttpStatus.UNPROCESSABLE_CONTENT.value(),
                        ApiErrorResponse.UNPROCESSABLE, INTERNAL_FAILURE_MESSAGE));
    }

    /**
     * Answers {@code 503} when this service refused to authorize against a replica it cannot vouch for
     * the age of.
     *
     * <p>This is the one fault a caller should retry, and the status says so. The decline rules read
     * {@code card_xref} and {@code account_credit_snapshot}, which are copies kept current by
     * state-change events, and a copy whose events stopped arriving keeps answering with whatever it
     * last knew. Once the replica catches up the same request receives a real decision, which is why a
     * retryable status is the honest answer and {@code 500} is not.
     *
     * <p>It is deliberately not a decline. {@code app/cbl/CBTRN02C.cbl:L385-L420} defines exactly four
     * reject reasons and the published contract enumerates those four, so there is no fifth to report;
     * and a decline would tell the caller something false about the cardholder, whose card and account
     * were both valid. This service was the component unable to answer.
     *
     * <p>The message names the account and the window and no card number, so it is safe to log. The
     * body still carries one fixed text, because a caller learns nothing useful from the window.
     *
     * @param failure the refusal
     * @return {@code 503} carrying one fixed text
     */
    @ExceptionHandler(AuthorizationService.StaleReplicaException.class)
    public ResponseEntity<ApiErrorResponse> onStaleReplica(
            AuthorizationService.StaleReplicaException failure) {

        log.error("Refusing an authorization call: {}", failure.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        ApiErrorResponse.INTERNAL_FAILURE, INTERNAL_FAILURE_MESSAGE));
    }

    /**
     * Answers {@code 500} for any other fault.
     *
     * <p>The exception class reaches the log and the exception message does not, because a message
     * raised deep in a driver or a parser can quote a value this service holds.
     *
     * @param failure the fault
     * @return {@code 500} carrying one fixed text
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiErrorResponse> onInternalFailure(RuntimeException failure) {
        log.error("An authorization call failed with {}", failure.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        ApiErrorResponse.INTERNAL_FAILURE, INTERNAL_FAILURE_MESSAGE));
    }
}
