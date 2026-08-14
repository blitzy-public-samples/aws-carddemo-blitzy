package com.carddemo.card.api;

import com.carddemo.card.api.dto.ApiErrorResponse;
import com.carddemo.card.api.dto.CardValidationMessages;
import com.carddemo.card.domain.CardQueryService;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.Objects;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ResponseEntity.BodyBuilder;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns a failure of a card endpoint into an {@link ApiErrorResponse}.
 *
 * <p>The framework's own problem detail carries the resolved request path in its {@code instance}
 * member. Two of the three routes of this service carry a card token as a path variable and the list
 * route carries its paging cursor in a request header, so a body that echoed the request would put an
 * identifier naming one card in the response and in any log line built from it. A token discloses no
 * digit of a card number, and it still names one card for as long as its key stands. Every response
 * this class returns carries a route template and one fixed or validated text, and no value read from
 * the request.
 *
 * <p>Six outcomes, and only the last is a fault of this service.
 *
 * <ul>
 * <li>A query parameter or a request header that misses its constraint answers {@code 400} with the
 * text that constraint declares, which is a text of
 * {@code com.carddemo.card.api.dto.CardValidationMessages} for each of the values the list route
 * constrains.</li>
 * <li>A query parameter that could not be converted to the type its route declares answers
 * {@code 400}. The row count of the list route is the one request value of this service that is not
 * text, so it is the one value a conversion can fail on, and the conversion runs before any
 * constraint does.</li>
 * <li>A paging position or a row count the read cannot browse with answers {@code 400}. That is
 * {@link CardQueryService.UnusableListRequest}, whose message is always one of the declared texts.
 * The case a constraint cannot reach is a well-formed cursor naming no row, which only a read
 * discovers.</li>
 * <li>A missing required parameter answers {@code 400} with a fixed text. The account the list route
 * lists by is required, and the rule that admits the route reads the same parameter, so a request
 * without it is refused before this class sees it in every deployment that runs the filter chain.
 * This answer exists for the case where it is not.</li>
 * <li>A body that cannot be read at all answers {@code 400} with a fixed text. Nothing of the
 * unreadable body reaches the response: a parser message names the position it failed at and quotes
 * the characters around it, and those characters are a card number.</li>
 * <li>Any other fault answers {@code 500} with one fixed text.</li>
 * </ul>
 *
 * <p>The route template is the mapping pattern the dispatcher matched, read from the request
 * attribute {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}. A pattern is not request content:
 * it is one of the two templates this service declares, chosen by the dispatcher, and it holds no
 * value a caller sent. Reporting the collection template for every failure was wrong on the two
 * routes that name one card, where a caller reading {@code /cards} for a failure of
 * {@code /cards/{cardToken}} is told the wrong endpoint failed.
 *
 * <p>{@link #routeOf} falls back to the collection template when no pattern is available, which is
 * the case when a failure arises before the dispatcher matched one and the case when this class is
 * called outside a request. It also falls back when a pattern would not satisfy
 * {@link ApiErrorResponse}, whose constructor refuses a run of more than four digits. The template of
 * the two routes that carry a path variable is {@code /cards/{cardToken}}, which holds the variable
 * name and no digit, and the check is there so that a route added later cannot turn a handled failure
 * into an unhandled one.
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
 *
 * <p>Design decisions: {@code card-platform/docs/decision-log.md}.
 */
@RestControllerAdvice
public class CardApiExceptionHandler {

    /** Records one line per refusal, naming no card number and no submitted value. */
    private static final Logger log = LoggerFactory.getLogger(CardApiExceptionHandler.class);

    /** Causes rendered into one failure line, deepest first, before the chain is cut. */
    private static final int FAILURE_TYPE_DEPTH = 3;

    /** Text a response carries when a required request value did not arrive. */
    static final String MISSING_REQUEST_VALUE_MESSAGE =
            "The account to list cards for is required";

    /** Text a response carries when the request body could not be read as one update. */
    static final String UNREADABLE_BODY_MESSAGE =
            "The request body holds one card in JavaScript Object Notation (JSON) and could not be"
                    + " read as one";

    /** Text a response carries when the service faults. */
    static final String SERVICE_FAULT_MESSAGE = "The request could not be completed";

    /**
     * Text a response carries when the card table could not be reached.
     *
     * <p>The text names a condition a caller may retry and names no host, no driver and no statement.
     */
    static final String DEPENDENCY_UNAVAILABLE_MESSAGE =
            "The card store is not reachable, so this request may be retried";

    /**
     * Text a response carries when the protocol refused the call.
     *
     * <p>A method a route does not serve, a media type it does not read, a media type it cannot write
     * and a path that matches no route all carry this text. The status separates them, and a
     * {@code 405} also carries {@code Allow}. The text names neither the method nor the path
     * submitted.
     */
    static final String UNSUPPORTED_REQUEST_MESSAGE =
            "This route does not serve the method, path or media type the request named";

    /**
     * Longest run of digits a route template may hold, matching the bound {@link ApiErrorResponse}
     * enforces in its constructor.
     */
    private static final int MAXIMUM_ROUTE_DIGIT_RUN = 4;

    /**
     * Counts one request this service could not complete on infrastructure.
     *
     * <p>The two handlers that answer {@code 503} and {@code 500} reported those failures in a log line
     * and nowhere else, so nothing an operator watches moved when a read route could not reach the card
     * table. {@code domain/CardUpdateService} already counts the write path, and the write path never
     * arrives here: a write that failed while holding the row answers through its own outcome chain.
     * So this handler counts the failures that chain never sees, which are the reads, and the two
     * increments cannot overlap.
     *
     * <p>A refusal is not counted. Business refusals and protocol refusals answer from the handlers
     * above and leave this counter alone, because a counter that rises on a caller's bad request tells
     * an operator nothing about this service.
     */
    private final Counter infrastructureFailures;

    /**
     * Builds the handler with the one counter it records to.
     *
     * @param infrastructureFailures the counter named
     *                               {@code ObservabilityConfig#METRIC_CARD_FAILURES}
     */
    public CardApiExceptionHandler(
            @Qualifier("cardInfrastructureFailureCounter") Counter infrastructureFailures) {
        this.infrastructureFailures =
                Objects.requireNonNull(infrastructureFailures, "infrastructureFailures");
    }

    /**
     * Answers a query parameter or a request header that misses its constraint.
     *
     * <p>A class annotated {@code @Validated} validates through a proxy, which reports every
     * violation of one call as this one exception. The response carries one text, because
     * {@link ApiErrorResponse} carries one: the source holds one message field,
     * {@code WS-RETURN-MSG PIC X(75)} at {@code app/cbl/COCRDUPC.cbl:L173}, and displays one text at
     * a time.
     *
     * <p>The text is chosen in sorted order rather than in the order the validator reports, so one
     * request reads the same way on every run. Only the list route constrains a value this way, and
     * its three constraints carry three distinct texts.
     *
     * @param violation the reported violations
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and one constraint text
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> onConstraintViolation(
            ConstraintViolationException violation, HttpServletRequest request) {
        log.info("A card request carried a value one constraint refused");
        return badRequest(firstTextOf(violation), request);
    }

    /**
     * Answers a query parameter the framework could not convert to the type its route declares.
     *
     * <p>The row count of the list route is the one request value of this service that is not text.
     * Its conversion runs while the argument is resolved, which is before any constraint on it can
     * run, so a value that is no number never reaches {@code @Min} or {@code @Max} and never reaches
     * the read. Without this method that conversion failure would fall through to
     * {@link #onFault(Exception, HttpServletRequest)}, and a caller sending {@code pageSize=abc} would
     * read {@code 500} with a stack trace logged at error level: a request the caller could correct
     * reported as a fault of this service, raising an alert besides.
     *
     * <p>The submitted value reaches no response and no log line. The framework's own message quotes
     * it, and this method reads the exception for nothing but its type.
     *
     * @param mismatch the reported failure, whose message reaches no response body
     * @param request  the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and the declared text
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> onParameterTypeMismatch(
            MethodArgumentTypeMismatchException mismatch, HttpServletRequest request) {
        log.info("A card request carried a parameter that could not be read as a number");
        return badRequest(CardValidationMessages.ADDITIVE_PAGE_SIZE_NOT_A_NUMBER, request);
    }

    /**
     * Answers a paging position or a row count the read cannot browse with.
     *
     * <p>{@link CardQueryService.UnusableListRequest} carries one of the texts
     * {@link CardValidationMessages} declares and never a value read from the request, so its message
     * reaches the caller verbatim. Two of its three cases are also constrained at the boundary and are
     * refused before the read runs; the third is a well-formed cursor that resolves to no row, which
     * no constraint can discover because discovering it takes a read.
     *
     * <p>Without this method all three would leave an {@link IllegalArgumentException} falling
     * through to {@link #onFault(Exception, HttpServletRequest)}, so a caller paging from a cursor
     * this service no longer holds would read {@code 500} rather than the {@code 400} its own request
     * had earned.
     *
     * @param unusable the refusal, whose message is a declared text
     * @param request  the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and that text
     */
    @ExceptionHandler(CardQueryService.UnusableListRequest.class)
    public ResponseEntity<ApiErrorResponse> onUnusableListRequest(
            CardQueryService.UnusableListRequest unusable, HttpServletRequest request) {
        log.info("A card list named a paging position or a row count it could not browse with");
        String text = unusable.getMessage();
        return badRequest(text == null || text.isBlank() ? MISSING_REQUEST_VALUE_MESSAGE : text,
                request);
    }

    /**
     * Answers a request value that misses its constraint when the framework validates the method
     * itself rather than a proxy.
     *
     * @param violation the reported violations, read for nothing but their texts
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and one constraint text
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> onMethodValidation(
            HandlerMethodValidationException violation, HttpServletRequest request) {
        log.info("A card request carried a value one constraint refused");
        TreeSet<String> texts = new TreeSet<>();
        for (ParameterValidationResult result : violation.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String text = error.getDefaultMessage();
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return badRequest(texts.isEmpty() ? MISSING_REQUEST_VALUE_MESSAGE : texts.first(), request);
    }

    /**
     * Answers a required query parameter or request header that did not arrive.
     *
     * @param missing the reported absence, read for nothing but its type
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(MissingRequestValueException.class)
    public ResponseEntity<ApiErrorResponse> onMissingRequestValue(
            MissingRequestValueException missing, HttpServletRequest request) {
        log.info("A card request omitted a required request value");
        return badRequest(MISSING_REQUEST_VALUE_MESSAGE, request);
    }

    /**
     * Answers a request body that could not be read at all.
     *
     * @param unreadable the reported failure, whose message reaches no response body
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and one fixed text
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> onUnreadableBody(
            HttpMessageNotReadableException unreadable, HttpServletRequest request) {
        log.info("A card request carried a body that could not be read");
        return badRequest(UNREADABLE_BODY_MESSAGE, request);
    }

    /**
     * Answers a call the protocol refused, at the status and with the headers the framework named.
     *
     * <p>Four failures reach here, each raised before a route ran: a method the route does not serve,
     * a media type it does not read, a media type it cannot write, and a path that matches no route.
     * Each carries its own status and its own headers, and {@code Allow} on a {@code 405} is how a
     * caller learns which methods the route does serve. Answering {@code 500} instead would record a
     * caller's mistake as a fault of this service and invite an unsafe retry.
     *
     * <p>The body carries the route template, as every body this class writes does, so no resolved
     * path and therefore no card number reaches a caller through a refusal. A {@code 406} carries no
     * body at all: a caller that accepts no type this service writes cannot be sent one.
     *
     * @param failure the protocol refusal, read for its status and its headers
     * @param request the failing request, read for its mapping pattern alone
     * @return the status the framework named, carrying that status's headers
     */
    @ExceptionHandler({HttpRequestMethodNotSupportedException.class, HttpMediaTypeException.class,
            NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> onUnsupportedRequest(ErrorResponse failure,
            HttpServletRequest request) {
        HttpStatusCode status = failure.getStatusCode();
        HttpStatus resolved = HttpStatus.valueOf(status.value());

        log.info("Refusing a card call on the protocol, answering {}", status.value());
        BodyBuilder response = ResponseEntity.status(status).headers(failure.getHeaders());
        if (resolved == HttpStatus.NOT_ACCEPTABLE) {
            return response.build();
        }
        return response.contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(status.value(), UNSUPPORTED_REQUEST_MESSAGE,
                        routeOf(request)));
    }

    /**
     * Answers a card table this service could not reach.
     *
     * <p>The request was well formed and this service holds no defect, so neither {@code 400} nor
     * {@code 500} describes what happened. {@code 503} does, and it names a condition a caller may
     * retry. The three families named here are the connection this service could not open, the
     * transaction it could not begin and the statement the database gave up on.
     *
     * <p>The update route reaches the same status through its own outcome. A write that failed once
     * the row was held answers {@code 503} from {@code api/CardController#statusOf}, carrying the
     * text {@code app/cbl/COCRDUPC.cbl:L210} declares. That answer carries the update body and this
     * one carries the failure body, because a failure raised before or outside the outcome chain has
     * no outcome to report.
     *
     * @param failure the reported failure, read for its type alone and never logged whole
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 503} carrying the route template and the declared text
     */
    @ExceptionHandler({DataAccessResourceFailureException.class,
            CannotCreateTransactionException.class, QueryTimeoutException.class})
    public ResponseEntity<ApiErrorResponse> onDatastoreUnreachable(Exception failure,
            HttpServletRequest request) {
        log.error("A card request could not reach the card table. The failure was {}. Its message is not recorded, because a datastore message quotes the statement and the value that caused it.", failureType(failure));
        infrastructureFailures.increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.SERVICE_UNAVAILABLE.value(),
                        DEPENDENCY_UNAVAILABLE_MESSAGE, routeOf(request)));
    }

    /**
     * Answers any other fault.
     *
     * @param fault   the fault, whose message reaches no response body
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 500} carrying the route template and one fixed text
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> onFault(Exception fault, HttpServletRequest request) {
        log.error("A card request failed inside this service. The failure was {}. Its message is not recorded, because a message quotes the value that caused it.", failureType(fault));
        infrastructureFailures.increment();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        SERVICE_FAULT_MESSAGE, routeOf(request)));
    }

    /**
     * Reads the first constraint text of one reported set, in sorted order.
     *
     * @param violation the reported violations
     * @return the first text, or the fixed missing-value text when the set carries none
     */
    private static String firstTextOf(ConstraintViolationException violation) {
        TreeSet<String> texts = new TreeSet<>();
        if (violation.getConstraintViolations() != null) {
            for (ConstraintViolation<?> reported : violation.getConstraintViolations()) {
                String text = reported.getMessage();
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return texts.isEmpty() ? MISSING_REQUEST_VALUE_MESSAGE : texts.first();
    }

    /**
     * Builds one bad-request body.
     *
     * @param message the one text the response carries
     * @param request the failing request, read for its mapping pattern alone
     * @return {@code 400} carrying the route template and that text
     */
    private static ResponseEntity<ApiErrorResponse> badRequest(String message,
            HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ApiErrorResponse(HttpStatus.BAD_REQUEST.value(), message,
                        routeOf(request)));
    }

    /**
     * Reads the route template of a failing request.
     *
     * <p>The value is the mapping pattern the dispatcher matched, which it records in the request
     * attribute {@link HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE}. A pattern is one of the two
     * templates this service declares and holds no value a caller sent, so reporting it discloses
     * nothing. Reporting the collection template for every failure told a caller of
     * {@code GET /cards/{cardToken}} that {@code /cards} had failed.
     *
     * <p>Three cases fall back to the collection template, and each is a case where no pattern is
     * available or usable rather than a case where one is ignored: a null request, which is how this
     * class is called outside a request; an absent or non-text attribute, which is a failure raised
     * before the dispatcher matched a pattern; and a pattern {@link ApiErrorResponse} would refuse.
     * That last check exists because the constructor of that record throws on a run of more than four
     * digits, and an exception thrown inside an exception handler reaches a caller as an unhandled
     * failure. Two routes of this service carry a path variable, and the template of both is
     * {@code /cards/{cardToken}}, which holds the variable name and no digit. The check reads the
     * template the dispatcher matched and never the path a caller sent, so no card number can reach a
     * body through it, and a route added later cannot turn a handled failure into one this class
     * cannot answer.
     *
     * @param request the failing request, or {@code null}
     * @return the mapping pattern the dispatcher matched, otherwise
     *         {@link CardController#COLLECTION_ROUTE}
     */
    private static String routeOf(HttpServletRequest request) {
        if (request == null) {
            return CardController.COLLECTION_ROUTE;
        }

        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (!(pattern instanceof String template) || template.isBlank()) {
            return CardController.COLLECTION_ROUTE;
        }

        return holdsALongDigitRun(template) ? CardController.COLLECTION_ROUTE : template;
    }

    /**
     * Reports whether a route template holds a run of digits {@link ApiErrorResponse} would refuse.
     *
     * @param template the candidate template
     * @return true when the template holds more than {@value #MAXIMUM_ROUTE_DIGIT_RUN} digits in a row
     */
    private static boolean holdsALongDigitRun(String template) {
        int run = 0;
        for (int position = 0; position < template.length(); position++) {
            char character = template.charAt(position);
            run = character >= '0' && character <= '9' ? run + 1 : 0;
            if (run > MAXIMUM_ROUTE_DIGIT_RUN) {
                return true;
            }
        }
        return false;
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
