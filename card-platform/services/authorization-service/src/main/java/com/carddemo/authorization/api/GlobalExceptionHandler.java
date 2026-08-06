package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.AuthorizationService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.ObjectError;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Advice that answers a failed authorization call with one status code and one sanitized body.
 *
 * <p>The source answers every fault with the same four statements.
 * {@code app/cbl/CBTRN02C.cbl:L707-L711} displays a line, zeroes a timing field, moves {@code 999}
 * into an abend code and calls the language-environment abend service. More than twenty call sites
 * reach it, and it performs no cleanup. One handler per class of failure replaces that single
 * outcome.
 *
 * <p>A rejected record is a normal outcome in the source and not a fault.
 * {@code app/cbl/CBTRN02C.cbl:L229} tests the reject count, and
 * {@code app/cbl/CBTRN02C.cbl:L230} moves 4 into the return code of a run that rejected records.
 * {@link AuthorizationController} answers a decline as a value carrying {@code 422} and an
 * {@link AuthorizationResponse} body, so no handler here ever sees a decline.
 *
 * <p>A decline is none of these. {@link AuthorizationController} answers {@code 422} for one, from a
 * value the decision service returned, so no handler below ever sees a decline. Two bodies
 * therefore share {@code 422}, and a caller tells them apart by shape: a decline carries
 * {@code approved} and one reject code, while a refused request carries {@link ApiErrorResponse}.
 *
 * <p>No handler here copies a request value into a response. Each body carries a status code, one
 * fixed phrase, the verbatim texts {@code app/cbl/COTRN02C.cbl} moves into {@code WS-MESSAGE} and
 * the moment the response was built. No stack trace, no exception type and no identifier reaches a
 * caller. No value read from the request reaches one either, so a card number sent in the wrong
 * position is never reflected back.
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Records the shape of each failure. No line carries a message read from an exception. */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** The text a body the reader could not parse reports, in place of the parser's own message. */
    static final String UNREADABLE_BODY_MESSAGE =
            "Request body holds one authorization in JavaScript Object Notation...";

    /** The text a request refused after binding reports, in place of the refusal's own message. */
    static final String REFUSED_REQUEST_MESSAGE =
            "This request was refused before any decision was taken...";

    /** The text a call naming a media type this endpoint does not read or write reports. */
    static final String MEDIA_TYPE_MESSAGE =
            "This endpoint reads and writes one JavaScript Object Notation body...";

    /** The text a fault inside this service reports, in place of the exception's own message. */
    static final String INTERNAL_FAILURE_MESSAGE = "Unable to authorize this transaction...";

    /**
     * Answers {@code 422} for a request body whose fields failed Bean Validation.
     *
     * <p>Each text is the one {@code app/cbl/COTRN02C.cbl} moves into {@code WS-MESSAGE} for that
     * field, carried through unaltered. The eleven emptiness tests at
     * {@code app/cbl/COTRN02C.cbl:L251-L320} and the class tests that follow them are the source
     * conditions, and {@link AuthorizationRequest} declares every text.
     *
     * @param failure the binding failure the framework raised
     * @return {@code 422} carrying one text per failing field
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidBody(MethodArgumentNotValidException failure) {
        List<String> texts = distinctTextsOf(failure.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .toList());

        log.info("Rejecting an authorization request on {} field validations", texts.size());
        return unprocessable(ApiErrorResponse.VALIDATION_FAILED, texts);
    }

    /**
     * Answers {@code 422} for a validation failure the framework raised on a handler argument.
     *
     * <p>The framework reports this shape where a constraint sits on a method parameter and not on a
     * component of the request body. The texts are the same verbatim
     * {@code app/cbl/COTRN02C.cbl} texts, carried through unaltered.
     *
     * @param failure the argument validation failure the framework raised
     * @return {@code 422} carrying one text per failing constraint
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidArgument(
            HandlerMethodValidationException failure) {

        List<String> texts = distinctTextsOf(failure.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .toList());

        log.info("Rejecting an authorization request on {} argument validations", texts.size());
        return unprocessable(ApiErrorResponse.VALIDATION_FAILED, texts);
    }

    /**
     * Answers {@code 422} for a constraint failure raised outside request-body binding.
     *
     * <p>A validator invoked directly reports this shape. The texts are the same verbatim
     * {@code app/cbl/COTRN02C.cbl} texts, carried through unaltered.
     *
     * @param failure the constraint failure
     * @return {@code 422} carrying one text per failing constraint
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException failure) {

        List<String> texts = distinctTextsOf(failure.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList());

        log.info("Rejecting an authorization request on {} constraints", texts.size());
        return unprocessable(ApiErrorResponse.VALIDATION_FAILED, texts);
    }

    /**
     * Answers {@code 400} for a body the reader could not parse.
     *
     * <p>ADDITIVE. The source has no matching condition: a 3270 terminal running under Customer
     * Information Control System (CICS) sends fixed-width map fields, which cannot arrive
     * unparseable. The parser quotes the text it stopped on, and that text can hold a card number.
     * The body therefore carries {@link #UNREADABLE_BODY_MESSAGE}, and the log line quotes nothing.
     *
     * @param failure the read failure the framework raised
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
     * Answers {@code 422} for a request this service refused after binding and before deciding.
     *
     * <p>Four refusals reach here. Each needs a stored row to detect, so no constraint on
     * {@link AuthorizationRequest} expresses it. A request naming neither identifier reproduces
     * the {@code WHEN OTHER} branch at {@code app/cbl/COTRN02C.cbl:L224-L229}. An account
     * identifier resolving no card reproduces the {@code NOTFND} limb at
     * {@code app/cbl/COTRN02C.cbl:L591-L592}. A supplied account identifier disagreeing with the
     * one the card resolved, and a capture moment outside the window
     * {@code com.carddemo.authorization.domain.OriginTimestampWindow} holds, are both additive.
     *
     * <p>The body carries {@link #REFUSED_REQUEST_MESSAGE}. A refusal message can quote the value
     * it refused, so no handler here puts one in a response or in a log line.
     *
     * @param failure the refusal the decision path raised
     * @return {@code 422} carrying one fixed text
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> onRefusedRequest(IllegalArgumentException failure) {
        log.info("Refusing an authorization request before a decision was taken");
        return unprocessable(ApiErrorResponse.UNPROCESSABLE, List.of(REFUSED_REQUEST_MESSAGE));
    }

    /**
     * Answers the status the framework named for a call whose media type this endpoint refuses.
     *
     * <p>ADDITIVE: a 3270 map field carries no media type, so the source has no matching condition.
     * A request declaring a content type this endpoint does not read answers {@code 415}. A request
     * accepting no type this endpoint writes answers {@code 406}, and that status travels alone:
     * such a caller cannot be sent a body either.
     *
     * @param failure the media type refusal the framework raised, carrying its own status
     * @return {@code 415} or {@code 406} carrying one fixed text
     */
    @ExceptionHandler(HttpMediaTypeException.class)
    public ResponseEntity<ApiErrorResponse> onUnsupportedMediaType(HttpMediaTypeException failure) {
        HttpStatusCode status = failure.getStatusCode();

        log.info("Refusing an authorization call on its media type, answering {}", status.value());
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(status.value(), ApiErrorResponse.UNPROCESSABLE,
                        MEDIA_TYPE_MESSAGE));
    }

    /**
     * Answers {@code 503} when a dependency this call needs was unreachable or too old to read.
     *
     * <p>These are the faults {@code app/cbl/CBTRN02C.cbl:L707-L711} answers with an abend. Four
     * come from the datastore: an unreachable host, a transaction this service could not open, a
     * lock it could not take and a statement that timed out. The fifth is a projection row older
     * than the configured window. {@code 503} is the one status a caller should present the same
     * request against again.
     *
     * <p>The body carries {@link #INTERNAL_FAILURE_MESSAGE}. The log line names the type and never
     * the message: a message raised inside a driver can quote a value this service holds.
     *
     * @param failure the unavailable dependency
     * @return {@code 503} carrying one fixed text
     */
    @ExceptionHandler({AuthorizationService.StaleReplicaException.class,
            DataAccessResourceFailureException.class,
            TransientDataAccessException.class,
            CannotCreateTransactionException.class})
    public ResponseEntity<ApiErrorResponse> onUnavailableDependency(RuntimeException failure) {
        log.error("Refusing an authorization call, a dependency is unavailable: {}",
                failure.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiErrorResponse.of(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        ApiErrorResponse.INTERNAL_FAILURE, INTERNAL_FAILURE_MESSAGE));
    }

    /**
     * Answers {@code 500} for any fault no handler above claimed. Last resort.
     *
     * <p>The log line names the type and the body carries {@link #INTERNAL_FAILURE_MESSAGE}: a
     * message raised deep inside a driver or a parser can quote a value this service holds.
     *
     * @param failure the fault
     * @return {@code 500} carrying one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onUnexpectedFailure(Exception failure) {
        log.error("An authorization call failed with {}", failure.getClass().getName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiErrorResponse.of(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        ApiErrorResponse.INTERNAL_FAILURE, INTERNAL_FAILURE_MESSAGE));
    }

    /**
     * Builds a {@code 422} response carrying one phrase and one or more texts.
     *
     * @param phrase one of the three phrases {@link ApiErrorResponse} declares
     * @param texts  one text per failing field, never empty
     * @return the response
     */
    private static ResponseEntity<ApiErrorResponse> unprocessable(String phrase,
            List<String> texts) {

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of(HttpStatus.UNPROCESSABLE_CONTENT.value(), phrase, texts));
    }

    /**
     * Sorts and deduplicates the texts one failure reported, dropping any that is absent or blank.
     *
     * <p>Sorting fixes the order, so one request reads the same body twice. A failure reporting no
     * usable text falls back to {@link ApiErrorResponse#VALIDATION_FAILED}, which holds the body to
     * the one text its schema requires.
     *
     * @param reported the texts the failure reported, any of which may be absent or blank
     * @return the texts to publish, sorted, deduplicated and never empty
     */
    private static List<String> distinctTextsOf(List<String> reported) {
        List<String> texts = new ArrayList<>(new TreeSet<>(reported.stream()
                .filter(text -> text != null && !text.isBlank())
                .toList()));

        return texts.isEmpty() ? List.of(ApiErrorResponse.VALIDATION_FAILED) : texts;
    }

    /**
     * Machine-readable body of every failed authorization call.
     *
     * <p>The source has no error body to reproduce. It moves one text into {@code WS-MESSAGE} and
     * redisplays the screen, as {@code app/cbl/COTRN02C.cbl:L254-L320} does eleven times over. This
     * record carries those same texts to a caller that has no screen.
     *
     * <p>Four components and nothing else. No component echoes the request path, the query string or
     * a header. A card number sent in the wrong position therefore reaches no caller and no log
     * through this body. {@code src/main/resources/openapi.yaml} describes the same four.
     *
     * @param status    the Hypertext Transfer Protocol status code of the response
     * @param error     a short fixed phrase naming the class of failure. The value comes from a
     *                  constant of this record and never from caller-supplied text
     * @param messages  one text per failing field, each reproduced from the source paragraph that
     *                  emits it. Never {@code null} and never empty
     * @param timestamp the moment the response was built
     */
    public record ApiErrorResponse(int status, String error, List<String> messages,
            Instant timestamp) {

        /** The {@link #error()} phrase of a request whose fields failed validation. */
        public static final String VALIDATION_FAILED = "Validation failed";

        /** The {@link #error()} phrase of a request this service could not process. */
        public static final String UNPROCESSABLE = "Unprocessable request";

        /** The {@link #error()} phrase of a fault inside this service. */
        public static final String INTERNAL_FAILURE = "Internal failure";

        /**
         * Copies the message list and refuses an incomplete body.
         *
         * @throws NullPointerException     when any component is {@code null}
         * @throws IllegalArgumentException when {@code messages} is empty
         */
        public ApiErrorResponse {
            Objects.requireNonNull(error, "error must be present");
            Objects.requireNonNull(messages, "messages must be present");
            Objects.requireNonNull(timestamp, "timestamp must be present");
            if (messages.isEmpty()) {
                throw new IllegalArgumentException(
                        "messages carries one text per failing field and is never empty");
            }
            messages = List.copyOf(messages);
        }

        /**
         * Builds an error body carrying one or more field texts.
         *
         * @param status   the status code of the response
         * @param error    one of the three phrases this record declares
         * @param messages one text per failing field
         * @return the error body, stamped with the current moment
         */
        public static ApiErrorResponse of(int status, String error, List<String> messages) {
            return new ApiErrorResponse(status, error, messages, Instant.now());
        }

        /**
         * Builds an error body carrying one text.
         *
         * @param status  the status code of the response
         * @param error   one of the three phrases this record declares
         * @param message the one text
         * @return the error body, stamped with the current moment
         */
        public static ApiErrorResponse of(int status, String error, String message) {
            return of(status, error, List.of(message));
        }
    }
}
