package com.carddemo.exception;

import com.carddemo.dto.ErrorResponse;
import com.carddemo.dto.ErrorResponse.FieldErrorDetail;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Centralized REST exception handler for the AWS CardDemo COBOL&rarr;Java migration.
 *
 * <p>This {@link RestControllerAdvice} is the single choke point that converts <em>every</em>
 * exception escaping the controller/service layers into the application's one canonical error
 * envelope &mdash; {@link ErrorResponse} &mdash; serialized as JSON with the correct HTTP status.
 * It is the Java replacement for the legacy COBOL message copybooks
 * {@code app/cpy/CSMSG01Y.cpy} ({@code CCDA-COMMON-MESSAGES}: human-readable status text such as the
 * "Invalid key pressed. Please see below..." prompt) and {@code app/cpy/CSMSG02Y.cpy}
 * ({@code ABEND-DATA}: the mainframe abend code / culprit / reason / message control block). The
 * common message text maps to {@link ErrorResponse#message()}, while the abend-style fatal path maps
 * to the generic HTTP&nbsp;500 response produced by {@link #handleUnexpected}.</p>
 *
 * <h2>Authority</h2>
 * <ul>
 *   <li>AAP &sect;0.3.2 &mdash; "Global exception handling &mdash; a {@code @RestControllerAdvice}
 *       translates domain exceptions into standardized error DTOs, replacing the
 *       {@code CSMSG01Y}/{@code CSMSG02Y} message copybooks".</li>
 *   <li>AAP &sect;0.4.1.5 &mdash; {@code exception/} package: domain exceptions &rarr;
 *       400/401/403/404/409.</li>
 *   <li>AAP &sect;0.6.6 &mdash; optimistic-locking conflict surfaced as HTTP&nbsp;409.</li>
 *   <li>AAP &sect;0.6.8 &mdash; PII suppression: never leak CVV, full SSN, passwords, stack traces,
 *       SQL, or Hibernate/internal details to clients.</li>
 * </ul>
 *
 * <h2>Status mapping</h2>
 * <table border="1">
 *   <caption>Exception &rarr; HTTP status</caption>
 *   <tr><th>HTTP status</th><th>Exception(s)</th></tr>
 *   <tr><td>400 Bad Request</td>
 *       <td>{@link MethodArgumentNotValidException}, {@link ConstraintViolationException},
 *           {@code ValidationException}, {@code BusinessRuleException},
 *           {@link HttpMessageNotReadableException}, {@link MethodArgumentTypeMismatchException},
 *           {@link MissingServletRequestParameterException}</td></tr>
 *   <tr><td>401 Unauthorized</td><td>{@link AuthenticationException}</td></tr>
 *   <tr><td>403 Forbidden</td><td>{@link AccessDeniedException}</td></tr>
 *   <tr><td>404 Not Found</td><td>{@code ResourceNotFoundException}</td></tr>
 *   <tr><td>409 Conflict</td>
 *       <td>{@code ConcurrentModificationException} (the CardDemo domain type),
 *           {@link ObjectOptimisticLockingFailureException}</td></tr>
 *   <tr><td>500 Internal Server Error</td><td>{@link Exception} (catch-all fallback)</td></tr>
 * </table>
 *
 * <h2>Authentication / authorization boundary (coordination with {@code security/SecurityConfig})</h2>
 * <p>This advice runs <strong>inside</strong> the Spring {@code DispatcherServlet}, so it only catches
 * authentication ({@link AuthenticationException}) and authorization ({@link AccessDeniedException})
 * failures that propagate out of a controller/service <em>invocation</em> &mdash; for example a
 * {@code BadCredentialsException} thrown by {@code AuthService} on the signon path, or a
 * {@code @PreAuthorize("hasRole('ADMIN')")} method-security denial (in Spring Security&nbsp;6 the thrown
 * {@code AuthorizationDeniedException} extends {@link AccessDeniedException}, so it is covered here).</p>
 * <p>Authentication/authorization failures that occur <strong>earlier, within the Spring Security filter
 * chain</strong> (most notably an invalid or expired JWT rejected by {@code JwtAuthenticationFilter})
 * are emitted <em>before</em> the request reaches the {@code DispatcherServlet} and are therefore
 * <strong>NOT</strong> handled by this advice. Those must be handled by an
 * {@code AuthenticationEntryPoint} (401) and an {@code AccessDeniedHandler} (403) wired in
 * {@code security/SecurityConfig} (owned by the {@code security} agent). To keep the API contract
 * uniform, that configuration MUST emit the SAME {@link ErrorResponse} JSON shape produced here, using
 * the same generic messages ({@value #MSG_AUTH_FAILED} / {@value #MSG_ACCESS_DENIED}).</p>
 *
 * <h2>PII &amp; internals suppression (AAP &sect;0.6.8 &mdash; MUST)</h2>
 * <ul>
 *   <li>Client-facing {@link ErrorResponse#message()} text is always either a curated domain message
 *       ({@code getMessage()} from {@code ResourceNotFoundException} / {@code ValidationException} /
 *       {@code BusinessRuleException}, which services populate from {@code MessageService}) or one of
 *       the generic {@code MSG_*} constants defined in this class.</li>
 *   <li>Stack traces, SQL, Hibernate messages, parser internals, and internal class names are
 *       <strong>never</strong> placed in the response body. The 500 fallback and the
 *       {@link ObjectOptimisticLockingFailureException} 409 path deliberately use generic text and log
 *       diagnostics server-side only.</li>
 *   <li>This handler never composes card CVV, full SSN, passwords, JWTs, or request bodies into log
 *       lines; {@code logback-spring.xml} masking is defense-in-depth, not a license to log secrets.</li>
 * </ul>
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>Contains no business logic; it only maps exceptions to {@link ErrorResponse}.</li>
 *   <li>The four CardDemo domain exceptions ({@code ResourceNotFoundException},
 *       {@code ValidationException}, {@code BusinessRuleException},
 *       {@code ConcurrentModificationException}) live in this same {@code com.carddemo.exception}
 *       package and are referenced WITHOUT an import. In particular the CardDemo
 *       {@code ConcurrentModificationException} is the optimistic-locking signal &mdash;
 *       {@code java.util.ConcurrentModificationException} is intentionally NEVER imported here.</li>
 *   <li>Uses the {@code jakarta.*} namespace throughout (Spring Boot&nbsp;3.2), never {@code javax.*}.</li>
 *   <li>Component-scanned automatically as a {@code @Component} via {@code @RestControllerAdvice}; no
 *       manual registration is required.</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Class logger. Used to record diagnostics server-side. Client errors (4xx) are logged at
     * {@code WARN} without a stack trace; the unexpected-error fallback (5xx) logs at {@code ERROR}
     * with the full throwable.
     */
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // ---------------------------------------------------------------------------------------------
    // Generic, framework-level client messages. These mirror the standardized-text intent of the
    // legacy CSMSG01Y / CSMSG02Y copybooks while guaranteeing no internal detail or PII leaks to the
    // client. Domain-specific text (404/400 domain paths) flows through the exception's own
    // getMessage(), which the service layer populates from MessageService.
    // ---------------------------------------------------------------------------------------------

    /** Generic 400 summary for field/validation failures (mirrors {@code CCDA-MSG-INVALID-KEY} intent). */
    private static final String MSG_VALIDATION_FAILED = "Validation failed for one or more fields.";

    /** Generic 400 message for an unparseable / malformed request body (never echoes parser detail). */
    private static final String MSG_MALFORMED_BODY = "Malformed request body.";

    /** Generic 401 message; deliberately uniform to prevent user/credential enumeration. */
    private static final String MSG_AUTH_FAILED = "Authentication failed.";

    /** Generic 403 message for authorization denials. */
    private static final String MSG_ACCESS_DENIED = "Access is denied.";

    /** Generic 409 message for optimistic-locking / concurrent-modification conflicts (AAP &sect;0.6.6). */
    private static final String MSG_CONFLICT =
            "The record was modified by another user. Please reload and retry.";

    /** Generic 500 message for unexpected failures (REST analogue of the COBOL ABEND path). */
    private static final String MSG_INTERNAL_ERROR = "An unexpected error occurred.";

    /** Fallback field name used when a constraint-violation property path cannot be resolved. */
    private static final String UNKNOWN_FIELD = "unknown";

    // =============================================================================================
    // Central response builder
    // =============================================================================================

    /**
     * Builds the standardized {@link ErrorResponse} body and wraps it in a {@link ResponseEntity}
     * carrying the supplied {@link HttpStatus}. This is the single place where the
     * {@link ErrorResponse} is constructed, guaranteeing every handler emits an identical shape with
     * {@code timestamp}, {@code status}, {@code error} (HTTP reason phrase), {@code message}, and
     * {@code path} populated.
     *
     * <p>When {@code fieldErrors} is {@code null} or empty the no-field-errors factory is used so the
     * {@code fieldErrors} JSON element is omitted entirely (the {@link ErrorResponse} record is
     * annotated {@code @JsonInclude(NON_NULL)}); otherwise the per-field detail is included.</p>
     *
     * @param status      the HTTP status to return; its numeric value and reason phrase populate
     *                    {@link ErrorResponse#status()} and {@link ErrorResponse#error()}
     * @param message     the client-facing detail message (already curated to be free of PII/internals)
     * @param request     the current request, used to populate {@link ErrorResponse#path()} via
     *                    {@link HttpServletRequest#getRequestURI()}; tolerated as {@code null}
     * @param fieldErrors per-field validation details, or {@code null}/empty when there are none
     * @return a {@link ResponseEntity} whose body is the populated {@link ErrorResponse}
     */
    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request,
                                                List<FieldErrorDetail> fieldErrors) {
        String path = (request != null) ? request.getRequestURI() : null;
        ErrorResponse body;
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, path);
        } else {
            body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, path, fieldErrors);
        }
        return ResponseEntity.status(status).body(body);
    }

    // =============================================================================================
    // 400 Bad Request
    // =============================================================================================

    /**
     * Handles Bean Validation failures on {@code @Valid @RequestBody} DTOs. Spring raises
     * {@link MethodArgumentNotValidException} when one or more request-body fields violate their
     * constraints; each violated field is surfaced in {@link ErrorResponse#fieldErrors()} as a
     * {@code field -> message} pair. The top-level {@code message} is the generic validation summary
     * mirroring the legacy {@code CCDA-MSG-INVALID-KEY} intent.
     *
     * @param ex      the validation exception carrying the binding result
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 with populated {@code fieldErrors}
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                      HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            String defaultMessage = fieldError.getDefaultMessage();
            fieldErrors.add(new FieldErrorDetail(
                    fieldError.getField(),
                    (defaultMessage != null) ? defaultMessage : MSG_VALIDATION_FAILED));
        }
        logClientError(request, HttpStatus.BAD_REQUEST, MSG_VALIDATION_FAILED);
        return build(HttpStatus.BAD_REQUEST, MSG_VALIDATION_FAILED, request, fieldErrors);
    }

    /**
     * Handles {@code @Validated} method/parameter validation failures. Spring raises
     * {@link ConstraintViolationException} for constraint violations on path/query parameters and
     * other method arguments. Each violation contributes a {@code field -> message} entry where the
     * field name is the last node of the violation's property path.
     *
     * @param ex      the constraint-violation exception
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 with populated {@code fieldErrors}
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        List<FieldErrorDetail> fieldErrors = new ArrayList<>();
        Set<ConstraintViolation<?>> violations = ex.getConstraintViolations();
        if (violations != null) {
            for (ConstraintViolation<?> violation : violations) {
                fieldErrors.add(new FieldErrorDetail(
                        lastNode(violation.getPropertyPath()),
                        violation.getMessage()));
            }
        }
        logClientError(request, HttpStatus.BAD_REQUEST, MSG_VALIDATION_FAILED);
        return build(HttpStatus.BAD_REQUEST, MSG_VALIDATION_FAILED, request, fieldErrors);
    }

    /**
     * Handles the CardDemo domain {@code ValidationException} &mdash; imperative/domain input
     * validation, most notably bad dates raised by {@code DateValidationService} (the migration of the
     * {@code CSUTLDTC} utility). The client-facing message is the exception's own curated message; any
     * per-field detail it carries is copied into {@link ErrorResponse#fieldErrors()}.
     *
     * <p>This is the CardDemo {@code com.carddemo.exception.ValidationException} (same package, no
     * import) &mdash; distinct from {@code jakarta.validation.ValidationException}.</p>
     *
     * @param ex      the domain validation exception
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400, with {@code fieldErrors} when the exception carries field detail
     */
    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(ValidationException ex,
                                                          HttpServletRequest request) {
        String message = (ex.getMessage() != null) ? ex.getMessage() : MSG_VALIDATION_FAILED;
        List<FieldErrorDetail> fieldErrors = toFieldErrors(ex.getFieldErrors());
        logClientError(request, HttpStatus.BAD_REQUEST, message);
        return build(HttpStatus.BAD_REQUEST, message, request, fieldErrors);
    }

    /**
     * Handles the CardDemo domain {@code BusinessRuleException} &mdash; a synchronous (online)
     * business-rule violation such as overlimit, an expired account, or insufficient available credit
     * (ported from the CICS online programs). Per the resolution in the {@code BusinessRuleException}
     * contract, these are <strong>HTTP 400</strong>, not 409. The client-facing message is the
     * exception's curated message.
     *
     * @param ex      the business-rule exception
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 with the curated business-rule message
     */
    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException ex,
                                                            HttpServletRequest request) {
        String message = (ex.getMessage() != null) ? ex.getMessage() : MSG_VALIDATION_FAILED;
        logClientError(request, HttpStatus.BAD_REQUEST, message);
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    /**
     * Handles an unreadable / malformed request body (for example invalid JSON, or a value that cannot
     * be bound to the target type). The client receives only the generic {@value #MSG_MALFORMED_BODY}
     * message; the parser's own message is deliberately NOT echoed because it can disclose internal
     * type names, field paths, or fragments of the offending payload.
     *
     * @param ex      the message-not-readable exception (its detail is intentionally not surfaced)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 with a generic message
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        logClientError(request, HttpStatus.BAD_REQUEST, MSG_MALFORMED_BODY);
        return build(HttpStatus.BAD_REQUEST, MSG_MALFORMED_BODY, request, null);
    }

    /**
     * Handles a type mismatch on a path or query parameter (for example a non-numeric value supplied
     * where a {@code Long} account id is expected). The parameter <em>name</em> is safe to echo; the
     * offending <em>value</em> is deliberately omitted because it could be sensitive.
     *
     * @param ex      the type-mismatch exception (its {@link MethodArgumentTypeMismatchException#getName()
     *                name} identifies the parameter)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 naming the invalid parameter
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        String message = "Invalid value for parameter '" + ex.getName() + "'.";
        logClientError(request, HttpStatus.BAD_REQUEST, message);
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    /**
     * Handles a missing required request parameter. The parameter name is safe to surface to the
     * client.
     *
     * @param ex      the missing-parameter exception
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 400 naming the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                HttpServletRequest request) {
        String message = "Missing required parameter '" + ex.getParameterName() + "'.";
        logClientError(request, HttpStatus.BAD_REQUEST, message);
        return build(HttpStatus.BAD_REQUEST, message, request, null);
    }

    // =============================================================================================
    // 401 Unauthorized
    // =============================================================================================

    /**
     * Handles authentication failures that propagate out of a controller/service invocation &mdash;
     * principally the {@code BadCredentialsException} thrown by {@code AuthService} on the signon path
     * (the migration of {@code COSGN00C}'s "Wrong Password" / "User not found" logic). For
     * <strong>anti-enumeration security</strong> a single generic message is returned regardless of
     * whether the user id or the password was wrong; the response never reveals which.
     *
     * <p>Boundary: invalid/expired-JWT failures raised inside the Spring Security filter chain
     * (before dispatch) are NOT seen here; they are handled by the {@code AuthenticationEntryPoint}
     * configured in {@code security/SecurityConfig}, which must return the same {@link ErrorResponse}
     * shape.</p>
     *
     * @param ex      the authentication exception (its detail is intentionally not surfaced)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 401 with a uniform generic message
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex,
                                                              HttpServletRequest request) {
        logClientError(request, HttpStatus.UNAUTHORIZED, MSG_AUTH_FAILED);
        return build(HttpStatus.UNAUTHORIZED, MSG_AUTH_FAILED, request, null);
    }

    // =============================================================================================
    // 403 Forbidden
    // =============================================================================================

    /**
     * Handles authorization denials surfacing through method security &mdash; for example a
     * {@code @PreAuthorize("hasRole('ADMIN')")} check failing on the admin-only {@code UserController}
     * CRUD endpoints. In Spring Security&nbsp;6 the thrown {@code AuthorizationDeniedException} extends
     * {@link AccessDeniedException}, so this single handler covers both.
     *
     * <p>Boundary: filter-chain-level access-denied is handled by {@code SecurityConfig}'s
     * {@code AccessDeniedHandler}; this advice covers method-security denials that surface through
     * dispatch.</p>
     *
     * @param ex      the access-denied exception (its detail is intentionally not surfaced)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 403 with a generic message
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex,
                                                            HttpServletRequest request) {
        logClientError(request, HttpStatus.FORBIDDEN, MSG_ACCESS_DENIED);
        return build(HttpStatus.FORBIDDEN, MSG_ACCESS_DENIED, request, null);
    }

    // =============================================================================================
    // 404 Not Found
    // =============================================================================================

    /**
     * Handles the CardDemo domain {@code ResourceNotFoundException} &mdash; a lookup by primary or
     * business key that yielded no row (for example "Account not found with id: ..."), the migration of
     * the legacy "... not found" screen paths. The exception's curated message is returned to the
     * client.
     *
     * @param ex      the not-found exception
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 404 with the curated not-found message
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex,
                                                                HttpServletRequest request) {
        String message = (ex.getMessage() != null) ? ex.getMessage() : "Resource not found.";
        logClientError(request, HttpStatus.NOT_FOUND, message);
        return build(HttpStatus.NOT_FOUND, message, request, null);
    }

    // =============================================================================================
    // 409 Conflict
    // =============================================================================================

    /**
     * Handles optimistic-locking / concurrent-modification conflicts, reproducing the legacy
     * {@code COACTUPC}/{@code COCRDUPC} {@code READ ... UPDATE} / {@code REWRITE} conflict guard
     * (AAP &sect;0.6.6). Two distinct conditions converge on HTTP&nbsp;409:
     * <ul>
     *   <li>the CardDemo domain {@code ConcurrentModificationException} (same package, no import),
     *       thrown explicitly by the service layer after an application-level change-detection check;
     *       and</li>
     *   <li>Spring's {@link ObjectOptimisticLockingFailureException}, raised automatically by Hibernate
     *       on flush when a {@code @Version} mismatch is detected.</li>
     * </ul>
     *
     * <p>For the domain exception the curated {@code getMessage()} is used when present; for the
     * Hibernate failure the generic {@value #MSG_CONFLICT} message is returned and the underlying
     * entity/SQL detail is logged server-side only &mdash; it is never leaked to the client.</p>
     *
     * <p>The parameter is typed {@link RuntimeException} because that is the nearest common supertype of
     * both handled exceptions; the runtime type is inspected to choose the message.</p>
     *
     * @param ex      the conflict exception (domain or Hibernate optimistic-lock failure)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 409 with a conflict message that never exposes internal detail
     */
    @ExceptionHandler({ConcurrentModificationException.class, ObjectOptimisticLockingFailureException.class})
    public ResponseEntity<ErrorResponse> handleConflict(RuntimeException ex,
                                                        HttpServletRequest request) {
        String message = MSG_CONFLICT;
        if (ex instanceof ConcurrentModificationException && ex.getMessage() != null) {
            message = ex.getMessage();
        }
        // 409 is a client-recoverable condition: log at WARN without a stack trace, recording only the
        // exception type server-side (never the Hibernate/entity/SQL detail) for diagnostics.
        log.warn("{} {} -> {} {} ({})",
                (request != null) ? request.getMethod() : "-",
                (request != null) ? request.getRequestURI() : "-",
                HttpStatus.CONFLICT.value(), HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, message, request, null);
    }

    // =============================================================================================
    // 500 Internal Server Error (catch-all fallback)
    // =============================================================================================

    /**
     * Catch-all fallback for any exception not matched by a more specific handler &mdash; the REST
     * analogue of the COBOL ABEND path / {@code CSMSG02Y} {@code ABEND-DATA}. The full exception
     * (including its stack trace) is logged at {@code ERROR} server-side for diagnosis, but the client
     * receives only the generic {@value #MSG_INTERNAL_ERROR} message: the exception message, class
     * name, stack trace, SQL, and any Hibernate/internal detail are <strong>never</strong> placed in
     * the response body (AAP &sect;0.6.8).
     *
     * <p>Because Spring resolves the most specific {@code @ExceptionHandler} first, this lowest-priority
     * handler runs only when no domain/framework handler above matched.</p>
     *
     * @param ex      the unexpected exception (logged with stack trace, never surfaced to the client)
     * @param request the current request (for {@link ErrorResponse#path()})
     * @return HTTP 500 with a generic message and no internal detail
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}",
                (request != null) ? request.getMethod() : "-",
                (request != null) ? request.getRequestURI() : "-",
                ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, MSG_INTERNAL_ERROR, request, null);
    }

    /**
     * Logs a client (4xx) error at {@code WARN} without a stack trace, capturing the request method,
     * URI, resolved status, and the (already-safe) client message for operational visibility. Per the
     * logging discipline (AAP &sect;0.6.8) 4xx conditions are expected, recoverable, client-driven
     * outcomes and therefore never carry a throwable/stack trace into the logs.
     *
     * @param request     the current request (tolerated as {@code null})
     * @param status      the HTTP status being returned
     * @param safeMessage the client-facing message; must already be free of PII/internals
     */
    private static void logClientError(HttpServletRequest request, HttpStatus status, String safeMessage) {
        String method = (request != null) ? request.getMethod() : "-";
        String uri = (request != null) ? request.getRequestURI() : "-";
        log.warn("{} {} -> {} {}: {}", method, uri, status.value(), status.getReasonPhrase(), safeMessage);
    }

    /**
     * Converts a domain {@code field name -> reason} map (as carried by
     * {@code ValidationException#getFieldErrors()}) into the {@link ErrorResponse}'s
     * {@link FieldErrorDetail} list, preserving the map's iteration order.
     *
     * @param fieldErrors the source map; may be {@code null} or empty
     * @return an ordered list of {@link FieldErrorDetail}, or {@code null} when the source is
     *         {@code null}/empty (so the {@code fieldErrors} JSON element is omitted)
     */
    private static List<FieldErrorDetail> toFieldErrors(Map<String, String> fieldErrors) {
        if (fieldErrors == null || fieldErrors.isEmpty()) {
            return null;
        }
        List<FieldErrorDetail> details = new ArrayList<>(fieldErrors.size());
        for (Map.Entry<String, String> entry : fieldErrors.entrySet()) {
            details.add(new FieldErrorDetail(entry.getKey(), entry.getValue()));
        }
        return details;
    }

    /**
     * Extracts the simple field name from a Bean Validation {@link Path} by returning the name of its
     * last node. For a violation reported against {@code updateAccount.request.creditLimit} this yields
     * {@code "creditLimit"}, which is the meaningful field name for the API consumer.
     *
     * @param propertyPath the constraint-violation property path; may be {@code null}
     * @return the last node's name, or {@link #UNKNOWN_FIELD} when it cannot be resolved
     */
    private static String lastNode(Path propertyPath) {
        String name = null;
        if (propertyPath != null) {
            for (Path.Node node : propertyPath) {
                if (node != null && node.getName() != null) {
                    name = node.getName();
                }
            }
        }
        return (name != null && !name.isEmpty()) ? name : UNKNOWN_FIELD;
    }
}
