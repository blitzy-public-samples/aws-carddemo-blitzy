package com.carddemo.authorization.api;

import com.carddemo.authorization.domain.AuthorizationService;
import com.carddemo.authorization.domain.CallerNotEntitledException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
 * <p>Two bodies share {@code 422}, and a caller tells them apart by shape. A decline carries
 * {@code approved} and one reject code. A refused request carries {@link ApiErrorResponse}.
 *
 * <p>No handler here copies a request value into a response. Each body carries a status code, one
 * fixed phrase, the verbatim texts {@code app/cbl/COTRN02C.cbl} moves into {@code WS-MESSAGE} and
 * the moment the response was built. No stack trace, no exception type and no identifier reaches a
 * caller. No value read from the request reaches one either, so a card number sent in the wrong
 * position is never reflected back.
 *
 * <p>The advice names no base package, so it also sees the failures raised before a handler is
 * chosen. Every health indicator of {@code config/ReadinessHealthConfig} reports its own dependency
 * down rather than throwing, so a management-port poll renders its own document and never arrives
 * here.
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
     * The text a call the protocol refused reports.
     *
     * <p>A method this route does not serve and a path that matches no route both carry it. The
     * status separates the two, and a {@code 405} also carries {@code Allow}. The text names neither
     * the method nor the path submitted.
     */
    static final String UNSUPPORTED_REQUEST_MESSAGE =
            "This route does not serve the method or path this request named...";

    /**
     * Answers {@code 422} for a request body whose fields failed Bean Validation.
     *
     * <p>The body carries one text, and that text is the one
     * {@code app/cbl/COTRN02C.cbl} moves into {@code WS-MESSAGE} for the failing field, carried
     * through unaltered. The eleven emptiness tests at {@code app/cbl/COTRN02C.cbl:L251-L320} and the
     * class tests that follow them are the source conditions, and {@link AuthorizationRequest}
     * declares every text.
     *
     * <p>A validator reports every failing component of one body at once, and the source reports one:
     * each edit performs {@code SEND-TRNADD-SCREEN} and returns to the terminal.
     * {@link #firstBySourceOrder(List)} therefore reduces the reported set to the text the source
     * would have emitted first.
     *
     * @param failure the binding failure the framework raised
     * @return {@code 422} carrying one text
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidBody(MethodArgumentNotValidException failure) {
        List<String> texts = firstBySourceOrder(failure.getBindingResult().getAllErrors().stream()
                .map(ObjectError::getDefaultMessage)
                .toList());

        log.info("Rejecting an authorization request on a field validation");
        return unprocessable(ApiErrorResponse.VALIDATION_FAILED, texts);
    }

    /**
     * Answers {@code 422} for a validation failure the framework raised on a handler argument.
     *
     * <p>The framework reports this shape where a constraint sits on a method parameter and not on a
     * component of the request body. The text is the same verbatim
     * {@code app/cbl/COTRN02C.cbl} text, carried through unaltered and reduced to one by
     * {@link #firstBySourceOrder(List)}.
     *
     * @param failure the argument validation failure the framework raised
     * @return {@code 422} carrying one text
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onInvalidArgument(
            HandlerMethodValidationException failure) {

        List<String> texts = firstBySourceOrder(failure.getAllErrors().stream()
                .map(MessageSourceResolvable::getDefaultMessage)
                .toList());

        log.info("Rejecting an authorization request on an argument validation");
        return unprocessable(ApiErrorResponse.VALIDATION_FAILED, texts);
    }

    /**
     * Answers {@code 422} for a constraint failure raised outside request-body binding.
     *
     * <p>A validator invoked directly reports this shape. The text is the same verbatim
     * {@code app/cbl/COTRN02C.cbl} text, carried through unaltered and reduced to one by
     * {@link #firstBySourceOrder(List)}.
     *
     * @param failure the constraint failure
     * @return {@code 422} carrying one text
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException failure) {

        List<String> texts = firstBySourceOrder(failure.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .toList());

        log.info("Rejecting an authorization request on a constraint");
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
     * Answers {@code 422} for an account identifier that resolves no cross-reference row.
     *
     * <p>The account branch of {@code VALIDATE-INPUT-KEY-FIELDS} reads the cross-reference by the
     * account identifier the caller supplied, at {@code app/cbl/COTRN02C.cbl:L200-L209}. The
     * {@code NOTFND} limb of that read moves
     * {@link AuthorizationRequest#ACCOUNT_ID_NOT_FOUND_MESSAGE} into {@code WS-MESSAGE} at
     * {@code app/cbl/COTRN02C.cbl:L591-L592} and re-sends the screen, so no transaction is captured.
     * The body carries that text verbatim.
     *
     * <p>The text is a fixed constant of {@link AuthorizationRequest} and holds no value read from
     * the request, so the refused identifier reaches no caller and no log line. This arm precedes
     * {@link #onRefusedRequest(IllegalArgumentException)}, whose fixed text would otherwise replace
     * a source text with a sanitized one.
     *
     * @param failure the refusal the decision path raised for an account with no cross-reference row
     * @return {@code 422} carrying the one source text
     */
    @ExceptionHandler(AuthorizationService.AccountNotFoundInCrossReferenceException.class)
    public ResponseEntity<ApiErrorResponse> onAccountNotFoundInCrossReference(
            AuthorizationService.AccountNotFoundInCrossReferenceException failure) {

        log.info("Refusing an authorization request whose account holds no cross-reference row");
        return unprocessable(ApiErrorResponse.UNPROCESSABLE,
                List.of(AuthorizationRequest.ACCOUNT_ID_NOT_FOUND_MESSAGE));
    }

    /**
     * Answers {@code 422} for a request this service refused after binding and before deciding.
     *
     * <p>One refusal reaches here. A request naming neither identifier reproduces the
     * {@code WHEN OTHER} branch at {@code app/cbl/COTRN02C.cbl:L224-L229}, and it needs no stored row
     * to detect. The account identifier that resolves no cross-reference row is answered by
     * {@link #onAccountNotFoundInCrossReference} above, carrying its own source text. A card number
     * that resolves no row reaches no refusal at all: it is reject reason {@code 0100} of
     * {@code app/cbl/CBTRN02C.cbl:L385-L387}, which {@code api/AuthorizationController} answers with
     * a decision body and this class never sees.
     *
     * <p>The body carries {@link #REFUSED_REQUEST_MESSAGE}. A refusal raised inside a library can
     * quote the value it refused, so this arm puts no message from the failure in a response or in a
     * log line.
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
     * Answers {@code 403} when the caller may not authorize against the subject its request resolved
     * to.
     *
     * <p>ADDITIVE. {@code app/cbl/CBTRN02C.cbl} authorizes a record the nightly feed supplied and has
     * no caller to entitle, and {@code app/cbl/COTRN02C.cbl} captures whatever card number the operator
     * keyed, so neither compares the identity that asked against the account or the card it named.
     *
     * <p>Not {@code 422}, and not a fifth reject reason. The four reject reasons at
     * {@code app/cbl/CBTRN02C.cbl:L385-L420} are outcomes of the transaction and each is published as a
     * {@code TransactionDeclined} event that consumers act on. This is an outcome of the caller: no
     * decision was taken, no row was written and no event exists.
     * {@code com.carddemo.authorization.domain.CallerNotEntitledException} carries the whole reasoning.
     *
     * <p>The body carries
     * {@value com.carddemo.authorization.domain.CallerNotEntitledException#DETAIL}, which is the same
     * text {@code config/SecurityConfig} writes for a denial the route itself refused. Naming the
     * account, the card or which comparison failed would confirm to an unentitled caller that the
     * subject exists, and that is how one credential enumerates the identifiers it does not hold. The
     * log line names no identifier either.
     *
     * @param denied the refusal the decision path raised
     * @return {@code 403} carrying one fixed text
     */
    @ExceptionHandler(CallerNotEntitledException.class)
    public ResponseEntity<ApiErrorResponse> onCallerNotEntitled(CallerNotEntitledException denied) {
        log.info("Refusing an authorization call: the identity does not hold the resolved subject");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiErrorResponse.of(HttpStatus.FORBIDDEN.value(), ApiErrorResponse.FORBIDDEN,
                        CallerNotEntitledException.DETAIL));
    }

    /**
     * Answers the status the framework named for a call whose media type this endpoint refuses.
     *
     * <p>ADDITIVE: a 3270 map field carries no media type, so the source has no matching condition.
     * A request declaring a content type this endpoint does not read answers {@code 415} carrying
     * {@link #MEDIA_TYPE_MESSAGE}, because that caller did declare a type it accepts and a body can
     * reach it.
     *
     * <p>A request accepting no type this endpoint writes answers {@code 406} and the status travels
     * alone. The reason is the refusal itself: the framework raised this because it could not select
     * a media type for the response, so attaching a body asks it to select one a second time for the
     * same request. There is no type it can pick, and the attempt turns a legible 406 into a second
     * failure. A bodyless 406 is also what {@code src/main/resources/openapi.yaml} documents, and a
     * status a caller cannot read the body of is better than a status it cannot read at all.
     *
     * @param failure the media type refusal the framework raised, carrying its own status
     * @return {@code 415} carrying one fixed text, or a bodyless {@code 406}
     */
    @ExceptionHandler(HttpMediaTypeException.class)
    public ResponseEntity<ApiErrorResponse> onUnsupportedMediaType(HttpMediaTypeException failure) {
        HttpStatusCode status = failure.getStatusCode();

        log.info("Refusing an authorization call on its media type, answering {}", status.value());
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) {
            return ResponseEntity.status(status).build();
        }
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
     * Answers a call the protocol refused, at the status and with the headers the framework named.
     *
     * <p>Two failures reach here, both raised before the handler ran: a method this route does not
     * serve, and a path that matches no route. A {@code 405} carries {@code Allow}, which is how a
     * caller learns that this route serves {@code POST} alone. Answering {@code 500} instead would
     * record a caller's mistake as a fault of this service and invite an unsafe retry of a call that
     * cannot succeed. {@link #onUnsupportedMediaType(HttpMediaTypeException)} answers the two
     * media-type refusals.
     *
     * @param failure the protocol refusal, read for its status and its headers
     * @return the status the framework named, carrying that status's headers and one fixed text
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class,
            NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> onUnsupportedRequest(ErrorResponse failure) {
        HttpStatusCode status = failure.getStatusCode();

        log.info("Refusing an authorization call on the protocol, answering {}", status.value());
        return ResponseEntity.status(status)
                .headers(failure.getHeaders())
                .body(ApiErrorResponse.of(status.value(), ApiErrorResponse.UNPROCESSABLE,
                        UNSUPPORTED_REQUEST_MESSAGE));
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
     * @param phrase one of the four phrases {@link ApiErrorResponse} declares
     * @param texts  one text per failing field, never empty
     * @return the response
     */
    private static ResponseEntity<ApiErrorResponse> unprocessable(String phrase,
            List<String> texts) {

        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT)
                .body(ApiErrorResponse.of(HttpStatus.UNPROCESSABLE_CONTENT.value(), phrase, texts));
    }

    /**
     * Picks the one text a refused request reports, the earliest of those reported in source order.
     *
     * <p>{@code app/cbl/COTRN02C.cbl} emits one text per rejected screen. Each edit moves its text
     * into {@code WS-MESSAGE} and performs {@code SEND-TRNADD-SCREEN}, which returns control to the
     * terminal, so the edits after it never run. A Bean Validation failure instead carries every
     * failing component of one body at once, and
     * {@link AuthorizationRequest#REJECTION_TEXTS_IN_SOURCE_ORDER} names the order the source would
     * have reached them in.
     *
     * <p>A text the list does not name follows every text it does, and ties among such texts break
     * on the text itself. One request therefore reads the same body twice. A failure reporting no
     * usable text falls back to {@link ApiErrorResponse#VALIDATION_FAILED}, which holds the body to
     * the one text its schema requires.
     *
     * @param reported the texts the failure reported, any of which may be absent or blank
     * @return one text, never empty
     */
    private static List<String> firstBySourceOrder(List<String> reported) {
        List<String> texts = reported.stream()
                .filter(text -> text != null && !text.isBlank())
                .sorted(Comparator
                        .comparingInt(GlobalExceptionHandler::sourcePositionOf)
                        .thenComparing(Comparator.naturalOrder()))
                .toList();

        return texts.isEmpty() ? List.of(ApiErrorResponse.VALIDATION_FAILED)
                : List.of(texts.getFirst());
    }

    /**
     * Reports where {@code app/cbl/COTRN02C.cbl} reaches the condition that emits one text.
     *
     * @param text a text one validation failure reported
     * @return the index of the text in {@link AuthorizationRequest#REJECTION_TEXTS_IN_SOURCE_ORDER},
     *         or a position after every entry of that list when it names no such text
     */
    private static int sourcePositionOf(String text) {
        int position = AuthorizationRequest.REJECTION_TEXTS_IN_SOURCE_ORDER.indexOf(text);

        return position < 0 ? AuthorizationRequest.REJECTION_TEXTS_IN_SOURCE_ORDER.size() : position;
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
         * The {@link #error()} phrase of a caller refused the subject it named.
         *
         * <p>The word matches the reason phrase of the status, and the phrase carries no detail beyond
         * it. A phrase naming the comparison that failed would tell an unentitled caller which
         * identifier it is missing.
         */
        public static final String FORBIDDEN = "Forbidden";

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
         * @param status   the status code of the response
         * @param error    one of the four phrases this record declares
         * @param messages one text per failing field
         * @return the error body, stamped with the current moment
         */
        public static ApiErrorResponse of(int status, String error, List<String> messages) {
            return new ApiErrorResponse(status, error, messages, Instant.now());
        }

        /**
         * @param status  the status code of the response
         * @param error   one of the four phrases this record declares
         * @param message the one text
         * @return the error body, stamped with the current moment
         */
        public static ApiErrorResponse of(int status, String error, String message) {
            return of(status, error, List.of(message));
        }
    }
}
