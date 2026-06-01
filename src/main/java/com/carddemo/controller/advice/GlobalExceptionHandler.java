package com.carddemo.controller.advice;

import com.carddemo.exception.AccountNotFoundException;
import com.carddemo.exception.DiscloseGroupNotFoundException;
import com.carddemo.exception.ErrorResponse;
import com.carddemo.exception.ExpiredAccountException;
import com.carddemo.exception.InvalidCardException;
import com.carddemo.exception.OverlimitException;
import com.carddemo.exception.TransactionValidationException;
import com.carddemo.util.CardNumberMasker;

import jakarta.persistence.OptimisticLockException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.batch.core.JobParametersInvalidException;
import org.springframework.batch.core.launch.NoSuchJobException;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Centralized REST exception handler that maps every application and framework
 * exception onto the uniform {@link ErrorResponse} JSON contract with the
 * appropriate HTTP status code.
 *
 * <p>This single {@code @RestControllerAdvice} bean consolidates what was, in the
 * original CardDemo COBOL/CICS system, dozens of scattered
 * {@code MOVE response-code TO WS-RESP-CD} statements and {@code EVALUATE} blocks
 * distributed across each online program. Every REST controller in the application
 * (the nine controllers described in AAP &sect;0.4.1.1, plus the batch-admin and
 * report controllers) reports errors through this one bean, so the entire REST
 * surface presents one consistent error shape regardless of the underlying failure.</p>
 *
 * <h2>Mapping summary</h2>
 * <table border="1">
 *   <caption>Exception &rarr; HTTP status &rarr; {@code ErrorResponse.code}</caption>
 *   <tr><th>Exception</th><th>HTTP</th><th>code</th></tr>
 *   <tr><td>{@link InvalidCardException} (COBOL 100)</td><td>400</td><td>"100"</td></tr>
 *   <tr><td>{@link AccountNotFoundException} (COBOL 101)</td><td>404</td><td>"101"</td></tr>
 *   <tr><td>{@link OverlimitException} (COBOL 102)</td><td>422</td><td>"102"</td></tr>
 *   <tr><td>{@link ExpiredAccountException} (COBOL 103)</td><td>422</td><td>"103"</td></tr>
 *   <tr><td>{@link TransactionValidationException} (parent fallback)</td><td>400</td><td>numeric</td></tr>
 *   <tr><td>{@link DiscloseGroupNotFoundException}</td><td>500</td><td>"DISCLOSE_GROUP_NOT_FOUND"</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException}</td><td>400</td><td>"VALIDATION_FAILED"</td></tr>
 *   <tr><td>{@link ConstraintViolationException}</td><td>400</td><td>"CONSTRAINT_VIOLATION"</td></tr>
 *   <tr><td>{@link HttpMessageNotReadableException}</td><td>400</td><td>"MALFORMED_REQUEST"</td></tr>
 *   <tr><td>{@link MissingServletRequestParameterException}</td><td>400</td><td>"MISSING_PARAMETER"</td></tr>
 *   <tr><td>{@link MethodArgumentTypeMismatchException}</td><td>400</td><td>"TYPE_MISMATCH"</td></tr>
 *   <tr><td>{@link ObjectOptimisticLockingFailureException}</td><td>409</td><td>"OPTIMISTIC_LOCK"</td></tr>
 *   <tr><td>{@link OptimisticLockException}</td><td>409</td><td>"OPTIMISTIC_LOCK"</td></tr>
 *   <tr><td>{@link DataIntegrityViolationException}</td><td>409</td><td>"DATA_INTEGRITY_VIOLATION"</td></tr>
 *   <tr><td>{@link IllegalArgumentException}</td><td>400</td><td>"ILLEGAL_ARGUMENT"</td></tr>
 *   <tr><td>{@link IllegalStateException}</td><td>422</td><td>"ILLEGAL_STATE"</td></tr>
 *   <tr><td>{@link NoSuchJobException}</td><td>404</td><td>"JOB_NOT_FOUND"</td></tr>
 *   <tr><td>{@link JobInstanceAlreadyCompleteException}</td><td>409</td><td>"JOB_ALREADY_COMPLETE"</td></tr>
 *   <tr><td>{@link JobExecutionAlreadyRunningException}</td><td>409</td><td>"JOB_ALREADY_RUNNING"</td></tr>
 *   <tr><td>{@link JobRestartException}</td><td>409</td><td>"JOB_RESTART_FAILED"</td></tr>
 *   <tr><td>{@link JobParametersInvalidException}</td><td>400</td><td>"INVALID_JOB_PARAMETERS"</td></tr>
 *   <tr><td>{@link AccessDeniedException}</td><td>403</td><td>"ACCESS_DENIED"</td></tr>
 *   <tr><td>{@link Exception} (catch-all)</td><td>500</td><td>"INTERNAL_ERROR"</td></tr>
 * </table>
 *
 * <h2>Design rules enforced by this class</h2>
 * <ul>
 *   <li><strong>PR-03</strong> &mdash; the COBOL validation codes 100/101/102/103 and
 *       their EXACT original messages (from {@code CBTRN02C.cbl} L386/398/411/418) are
 *       preserved verbatim through {@code ex.getCode()} / {@code ex.getMessage()}.</li>
 *   <li><strong>PR-22</strong> &mdash; optimistic-lock conflicts return the EXACT legacy
 *       message {@code "Record changed by some one else. Please review"} (from
 *       {@code COACTUPC.cbl} L522 / {@code COCRDUPC.cbl} L208), reproducing the VSAM
 *       {@code READ UPDATE}/{@code REWRITE} conflict semantics.</li>
 *   <li><strong>PR-28</strong> &mdash; only the Jakarta EE 10 namespace ({@code jakarta.*})
 *       is used; no {@code javax.*} imports.</li>
 *   <li><strong>PR-29</strong> &mdash; no field injection; this advice has no dependencies
 *       and therefore no constructor or fields.</li>
 * </ul>
 *
 * <h2>Logging policy</h2>
 * <ul>
 *   <li>4xx client errors are logged at {@code WARN} with no stack trace (no trailing
 *       {@code ex} argument).</li>
 *   <li>5xx server errors are logged at {@code ERROR} <em>with</em> the full stack trace
 *       (trailing {@code ex} argument): the {@link DiscloseGroupNotFoundException} handler
 *       and the catch-all {@link Exception} handler.</li>
 *   <li>Sensitive data (passwords, card numbers, SSNs, BCrypt hashes, JWTs, PII) is never
 *       logged. In particular, rejected field values are never logged or echoed back, and
 *       generic public-facing messages are used for parser- and database-level failures so
 *       internal details are not leaked to clients. As a defense-in-depth measure, the
 *       {@link IllegalStateException} and {@link IllegalArgumentException} handlers run their
 *       message through {@link CardNumberMasker#maskInMessage(String)} to mask any embedded
 *       PAN-like value before it reaches either the log or the client payload (F8).</li>
 * </ul>
 *
 * <h2>Authorization (403) is handled here; authentication (401) is not</h2>
 * <p>{@link AccessDeniedException} (403) raised by method security &mdash; the
 * {@code @PreAuthorize("hasRole('ADMIN')")} guards on {@code UserController} and
 * {@code BatchAdminController} (PR-18) &mdash; is thrown <em>during</em> handler-method
 * invocation, so it propagates into {@code @ControllerAdvice} and IS mapped here to a
 * {@code 403 Forbidden} {@link ErrorResponse}. Handling it explicitly also prevents the
 * catch-all {@link Exception} handler from incorrectly reporting an authorization denial as
 * {@code 500}.</p>
 * <p>{@code AuthenticationException} (401), by contrast, is raised inside the Spring Security
 * filter chain (by the {@code AuthenticationEntryPoint}) <em>before</em> the
 * {@code DispatcherServlet} and {@code @ControllerAdvice} are reached, so it is deliberately
 * NOT handled here; a handler for it would have no effect.</p>
 *
 * @see ErrorResponse
 * @see TransactionValidationException
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // =========================================================================
    // Phase 2: Custom domain exception handlers (PR-03 EXACT message preservation)
    //
    // The four COBOL validation-code subclasses (100/101/102/103) each get a
    // dedicated handler; Spring dispatches the MOST SPECIFIC matching type, so a
    // direct TransactionValidationException (e.g. code 109) falls through to the
    // parent handler below. ex.getMessage() yields the exact COBOL_MESSAGE literal.
    // =========================================================================

    /**
     * Handles {@link InvalidCardException} (COBOL validation code 100).
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "100"} and the exact COBOL
     * message {@code "INVALID CARD NUMBER FOUND"} (CBTRN02C.cbl L386, PR-03).</p>
     *
     * @param ex      the invalid-card exception carrying COBOL code 100
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(InvalidCardException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCard(
            InvalidCardException ex,
            HttpServletRequest request) {
        log.warn("Invalid card at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code(String.valueOf(ex.getCode()))
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link AccountNotFoundException} (COBOL validation code 101).
     *
     * <p>Maps to {@code 404 Not Found} with code {@code "101"}. The message is whatever
     * the thrower supplied &mdash; the exact COBOL literal {@code "ACCOUNT RECORD NOT FOUND"}
     * (CBTRN02C.cbl L398) or an enhanced {@code "Account not found: <id>"} variant (PR-03).</p>
     *
     * @param ex      the account-not-found exception carrying COBOL code 101
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 404} {@link ErrorResponse}
     */
    @ExceptionHandler(AccountNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleAccountNotFound(
            AccountNotFoundException ex,
            HttpServletRequest request) {
        log.warn("Account not found at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .code(String.valueOf(ex.getCode()))
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles {@link OverlimitException} (COBOL validation code 102).
     *
     * <p>Maps to {@code 422 Unprocessable Entity} with code {@code "102"} and the exact
     * COBOL message {@code "OVERLIMIT TRANSACTION"} (CBTRN02C.cbl L411, PR-03). The request
     * is well-formed but cannot be processed because it would exceed the credit limit.</p>
     *
     * @param ex      the overlimit exception carrying COBOL code 102
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 422} {@link ErrorResponse}
     */
    @ExceptionHandler(OverlimitException.class)
    public ResponseEntity<ErrorResponse> handleOverlimit(
            OverlimitException ex,
            HttpServletRequest request) {
        // F6/PR-03: log the full (possibly diagnostic) message server-side at WARN, but
        // ALWAYS return the EXACT COBOL literal to the client, regardless of whether the
        // exception was built with the no-arg or the diagnostic constructor.
        log.warn("Overlimit transaction at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .code(String.valueOf(ex.getCode()))
                .message(OverlimitException.COBOL_MESSAGE)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Handles {@link ExpiredAccountException} (COBOL validation code 103).
     *
     * <p>Maps to {@code 422 Unprocessable Entity} with code {@code "103"} and the exact
     * COBOL message {@code "TRANSACTION RECEIVED AFTER ACCT EXPIRATION"} (CBTRN02C.cbl L418,
     * PR-03 &mdash; the {@code "ACCT"} abbreviation is intentional and preserved).</p>
     *
     * @param ex      the expired-account exception carrying COBOL code 103
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 422} {@link ErrorResponse}
     */
    @ExceptionHandler(ExpiredAccountException.class)
    public ResponseEntity<ErrorResponse> handleExpiredAccount(
            ExpiredAccountException ex,
            HttpServletRequest request) {
        // F6/PR-03: log the full (possibly diagnostic) message server-side at WARN, but
        // ALWAYS return the EXACT COBOL literal to the client, regardless of whether the
        // exception was built with the no-arg or the diagnostic constructor.
        log.warn("Expired account at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .code(String.valueOf(ex.getCode()))
                .message(ExpiredAccountException.COBOL_MESSAGE)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    /**
     * Fallback handler for any {@link TransactionValidationException} that is NOT one of
     * the four specialized subclasses (100/101/102/103) &mdash; for example a direct throw
     * with COBOL code 109 ({@code 2800-UPDATE-ACCOUNT-REC} REWRITE {@code INVALID KEY}).
     *
     * <p>Maps to {@code 400 Bad Request}, surfacing the numeric reason code via
     * {@code String.valueOf(ex.getCode())}. Spring resolves the most specific
     * {@code @ExceptionHandler} first, so the subclass handlers above always take
     * precedence over this fallback.</p>
     *
     * @param ex      the unspecialized validation exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(TransactionValidationException.class)
    public ResponseEntity<ErrorResponse> handleTransactionValidation(
            TransactionValidationException ex,
            HttpServletRequest request) {
        log.warn("Transaction validation failure at {} (code={}): {}",
                request.getRequestURI(), ex.getCode(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code(String.valueOf(ex.getCode()))
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link DiscloseGroupNotFoundException} &mdash; a CRITICAL data-integrity
     * failure where the disclosure-group interest-rate lookup missed even after the
     * {@code DEFAULT}-group fallback retry ({@code CBACT04C.cbl} L443-L460,
     * {@code 9999-ABEND-PROGRAM}). This should never happen with correctly seeded
     * reference data.
     *
     * <p>Maps to {@code 500 Internal Server Error} with the symbolic code
     * {@code "DISCLOSE_GROUP_NOT_FOUND"}. Logged at {@code ERROR} with the full stack
     * trace because it indicates a server-side reference-data defect, not client input.</p>
     *
     * @param ex      the disclosure-group-not-found exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 500} {@link ErrorResponse}
     */
    @ExceptionHandler(DiscloseGroupNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDiscloseGroupNotFound(
            DiscloseGroupNotFoundException ex,
            HttpServletRequest request) {
        log.error("Disclose group not found (critical data integrity issue) at {}: {}",
                request.getRequestURI(), ex.getMessage(), ex);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("DISCLOSE_GROUP_NOT_FOUND")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // =========================================================================
    // Phase 3: Framework request-binding / validation exception handlers (all 400)
    // =========================================================================

    /**
     * Handles {@link MethodArgumentNotValidException} raised when a {@code @Valid}
     * {@code @RequestBody} fails Jakarta Bean Validation (for example a blank
     * {@code LoginRequest.userId}).
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "VALIDATION_FAILED"} and a
     * generic {@code "Validation failed"} message, plus a populated
     * {@link ErrorResponse.FieldError} list flattened from the framework
     * {@code BindingResult}.</p>
     *
     * <p><strong>Security:</strong> only the field <em>name</em> and the validation
     * <em>message</em> are captured. The rejected value is deliberately never copied into
     * the response or the log &mdash; it could contain a password, SSN, or other sensitive
     * input. The {@link ErrorResponse.FieldError} record is itself a two-argument
     * {@code (field, message)} type that has no slot for the rejected value, enforcing this
     * at the type level. The log records only the field-error count.</p>
     *
     * @param ex      the bean-validation exception carrying the binding result
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse} with per-field errors
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ErrorResponse.FieldError(
                        fe.getField(),
                        fe.getDefaultMessage()))
                .collect(Collectors.toList());
        log.warn("Validation failed at {} ({} field errors)",
                request.getRequestURI(), fieldErrors.size());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("VALIDATION_FAILED")
                .message("Validation failed")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link ConstraintViolationException} raised when a {@code @Validated}
     * controller's path or query parameters fail Jakarta Bean Validation.
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "CONSTRAINT_VIOLATION"}. The
     * Jakarta EE 10 {@code jakarta.validation.ConstraintViolationException} is used
     * (never {@code javax.validation.*}), per PR-28.</p>
     *
     * @param ex      the constraint-violation exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request) {
        log.warn("Constraint violation at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("CONSTRAINT_VIOLATION")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link HttpMessageNotReadableException} raised when the request body cannot
     * be deserialized (for example malformed JSON).
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "MALFORMED_REQUEST"} and a
     * GENERIC client-facing message. The specific parser cause is logged for diagnostics
     * only &mdash; it is never returned to the client because parser output can include
     * snippets of the (potentially sensitive) submitted payload.</p>
     *
     * @param ex      the message-not-readable exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request) {
        log.warn("Malformed request body at {}: {}",
                request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("MALFORMED_REQUEST")
                .message("Malformed JSON request body")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link MissingServletRequestParameterException} raised when a required
     * {@code @RequestParam} is absent.
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "MISSING_PARAMETER"}. The
     * parameter <em>name</em> is included in the message (parameter names are not
     * sensitive); no value is referenced.</p>
     *
     * @param ex      the missing-parameter exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(
            MissingServletRequestParameterException ex,
            HttpServletRequest request) {
        log.warn("Missing required parameter at {}: {}",
                request.getRequestURI(), ex.getParameterName());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("MISSING_PARAMETER")
                .message("Required parameter '" + ex.getParameterName() + "' is missing")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link MethodArgumentTypeMismatchException} raised when a path or query
     * parameter cannot be converted to the declared method-argument type (for example a
     * non-numeric value bound to a {@code Long} path variable).
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "TYPE_MISMATCH"}. The message
     * names the offending parameter and its expected type only; the actual rejected value
     * is never included because it could be sensitive. {@code getRequiredType()} is
     * null-guarded.</p>
     *
     * @param ex      the type-mismatch exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request) {
        String expectedType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "unknown";
        log.warn("Type mismatch at {}: parameter '{}' expected type {}",
                request.getRequestURI(),
                ex.getName(),
                expectedType);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("TYPE_MISMATCH")
                .message("Parameter '" + ex.getName() + "' must be of type " + expectedType)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // =========================================================================
    // Phase 4: Concurrency / data-integrity exception handlers (409)
    // =========================================================================

    /**
     * Handles {@link ObjectOptimisticLockingFailureException} &mdash; the Spring ORM
     * translation of a JPA {@code @Version} optimistic-lock conflict, raised when a
     * managed entity is updated concurrently (PR-22).
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "OPTIMISTIC_LOCK"} and the EXACT
     * legacy message {@code "Record changed by some one else. Please review"} (from
     * {@code COACTUPC.cbl} L522 / {@code COCRDUPC.cbl} L208), reproducing the original
     * CICS VSAM {@code READ UPDATE}/{@code REWRITE} conflict-detection behavior. The
     * message text must not be altered in spelling, punctuation, or spacing.</p>
     *
     * @param ex      the Spring optimistic-locking-failure exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            ObjectOptimisticLockingFailureException ex,
            HttpServletRequest request) {
        log.warn("Optimistic lock conflict at {}: entity={}",
                request.getRequestURI(),
                ex.getPersistentClassName());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("OPTIMISTIC_LOCK")
                .message("Record changed by some one else. Please review")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles the raw Jakarta Persistence {@link OptimisticLockException} for the rare
     * case where it escapes unwrapped (Spring normally translates it into
     * {@link ObjectOptimisticLockingFailureException}).
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "OPTIMISTIC_LOCK"} and the same
     * EXACT PR-22 message {@code "Record changed by some one else. Please review"}. Uses
     * the Jakarta EE 10 {@code jakarta.persistence.OptimisticLockException} per PR-28.</p>
     *
     * @param ex      the Jakarta persistence optimistic-lock exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(OptimisticLockException.class)
    public ResponseEntity<ErrorResponse> handleJakartaOptimisticLock(
            OptimisticLockException ex,
            HttpServletRequest request) {
        log.warn("Jakarta optimistic lock conflict at {}", request.getRequestURI());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("OPTIMISTIC_LOCK")
                .message("Record changed by some one else. Please review")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link DataIntegrityViolationException} raised when a persistence operation
     * violates a database integrity constraint (for example a duplicate {@code userId} on
     * {@code POST /api/admin/users}, per {@code COUSR01C.cbl} L263, or a duplicate
     * transaction id, per {@code COBIL00C.cbl} L536).
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "DATA_INTEGRITY_VIOLATION"} and a
     * GENERIC client-facing message. The specific database cause is logged for diagnostics
     * only &mdash; it is never returned to the client because it can leak column names and
     * constraint internals.</p>
     *
     * @param ex      the data-integrity-violation exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex,
            HttpServletRequest request) {
        log.warn("Data integrity violation at {}: {}",
                request.getRequestURI(),
                ex.getMostSpecificCause().getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("DATA_INTEGRITY_VIOLATION")
                .message("Resource already exists or violates a data integrity constraint")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    // =========================================================================
    // Phase 5: Generic programming-error handlers
    // =========================================================================

    /**
     * Handles {@link IllegalArgumentException} raised by service-layer argument validation.
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "ILLEGAL_ARGUMENT"}, passing the
     * exception message through to the client.</p>
     *
     * @param ex      the illegal-argument exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request) {
        // F8: defensively mask any embedded card/PAN-like value before it reaches the log
        // or the client-facing payload.
        String safeMessage = CardNumberMasker.maskInMessage(ex.getMessage());
        log.warn("Illegal argument at {}: {}", request.getRequestURI(), safeMessage);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("ILLEGAL_ARGUMENT")
                .message(safeMessage)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Handles {@link IllegalStateException} raised by business-rule violations &mdash; for
     * example {@code BillPaymentService} rejecting a payment when the balance is not
     * positive ({@code COBIL00C.cbl} L201, {@code "You have nothing to pay..."}).
     *
     * <p>Maps to {@code 422 Unprocessable Entity} with code {@code "ILLEGAL_STATE"}, passing
     * the business-rule message through to the client.</p>
     *
     * @param ex      the illegal-state exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 422} {@link ErrorResponse}
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request) {
        // F8: StatementService and other components may embed a card number in the
        // IllegalStateException message; defensively mask any PAN-like value before it
        // reaches the log or the client-facing payload. Legitimate business messages with no
        // PAN (e.g. "You have nothing to pay...") pass through unchanged.
        String safeMessage = CardNumberMasker.maskInMessage(ex.getMessage());
        log.warn("Illegal state at {}: {}", request.getRequestURI(), safeMessage);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.UNPROCESSABLE_ENTITY.value())
                .code("ILLEGAL_STATE")
                .message(safeMessage)
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    // =========================================================================
    // Phase 6: Spring Batch launch/repository exception handlers (defense-in-depth)
    //
    // BatchAdminController declares LOCAL @ExceptionHandlers for these, which Spring
    // resolves before these global ones for its own endpoints. These globals provide
    // coverage when any other controller (e.g. ReportService via JobLauncher.run())
    // triggers a batch launch failure.
    // =========================================================================

    /**
     * Handles {@link NoSuchJobException} raised when a launch request names a job that is
     * not registered in the {@code JobRegistry}.
     *
     * <p>Maps to {@code 404 Not Found} with code {@code "JOB_NOT_FOUND"}.</p>
     *
     * @param ex      the no-such-job exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 404} {@link ErrorResponse}
     */
    @ExceptionHandler(NoSuchJobException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchJob(
            NoSuchJobException ex,
            HttpServletRequest request) {
        log.warn("Job not found at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.NOT_FOUND.value())
                .code("JOB_NOT_FOUND")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * Handles {@link JobInstanceAlreadyCompleteException} raised when a job instance with
     * the same identifying parameters has already completed successfully and cannot be
     * rerun.
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "JOB_ALREADY_COMPLETE"}.</p>
     *
     * @param ex      the job-instance-already-complete exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(JobInstanceAlreadyCompleteException.class)
    public ResponseEntity<ErrorResponse> handleJobAlreadyComplete(
            JobInstanceAlreadyCompleteException ex,
            HttpServletRequest request) {
        log.warn("Job instance already complete at {}: {}",
                request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("JOB_ALREADY_COMPLETE")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link JobExecutionAlreadyRunningException} raised when a launch is attempted
     * for a job execution that is already in progress.
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "JOB_ALREADY_RUNNING"}.</p>
     *
     * @param ex      the job-execution-already-running exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(JobExecutionAlreadyRunningException.class)
    public ResponseEntity<ErrorResponse> handleJobAlreadyRunning(
            JobExecutionAlreadyRunningException ex,
            HttpServletRequest request) {
        log.warn("Job execution already running at {}: {}",
                request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("JOB_ALREADY_RUNNING")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link JobRestartException} raised when a job that is not restartable (or is
     * otherwise in a non-restartable state) is asked to restart.
     *
     * <p>Maps to {@code 409 Conflict} with code {@code "JOB_RESTART_FAILED"}.</p>
     *
     * @param ex      the job-restart exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 409} {@link ErrorResponse}
     */
    @ExceptionHandler(JobRestartException.class)
    public ResponseEntity<ErrorResponse> handleJobRestart(
            JobRestartException ex,
            HttpServletRequest request) {
        log.warn("Job restart failed at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.CONFLICT.value())
                .code("JOB_RESTART_FAILED")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Handles {@link JobParametersInvalidException} raised when supplied job parameters
     * fail the job's parameter validation.
     *
     * <p>Maps to {@code 400 Bad Request} with code {@code "INVALID_JOB_PARAMETERS"}.</p>
     *
     * @param ex      the invalid-job-parameters exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 400} {@link ErrorResponse}
     */
    @ExceptionHandler(JobParametersInvalidException.class)
    public ResponseEntity<ErrorResponse> handleJobParametersInvalid(
            JobParametersInvalidException ex,
            HttpServletRequest request) {
        log.warn("Invalid job parameters at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.BAD_REQUEST.value())
                .code("INVALID_JOB_PARAMETERS")
                .message(ex.getMessage())
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // =========================================================================
    // Authorization handler (403) — method-security @PreAuthorize denials
    // =========================================================================

    /**
     * Handles {@link AccessDeniedException} raised by method security &mdash; the
     * {@code @PreAuthorize("hasRole('ADMIN')")} guards on {@code UserController} and
     * {@code BatchAdminController} (PR-18). The exception is thrown during handler-method
     * invocation, so it propagates into this {@code @ControllerAdvice}.
     *
     * <p>Maps to {@code 403 Forbidden} with code {@code "ACCESS_DENIED"} and a GENERIC
     * client-facing message (the framework default is simply {@code "Access Denied"}; we do
     * not echo any request detail). Handling it explicitly is required by the checkpoint
     * scope and also prevents the catch-all {@link Exception} handler from mis-reporting an
     * authorization denial as {@code 500}. Logged at {@code WARN} with no stack trace, since
     * a denied request is an expected client-side condition rather than a server fault.</p>
     *
     * @param ex      the access-denied exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 403} {@link ErrorResponse}
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request) {
        log.warn("Access denied at {}: {}", request.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.FORBIDDEN.value())
                .code("ACCESS_DENIED")
                .message("Access is denied")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // =========================================================================
    // Phase 7: Catch-all handler (LAST — supertype of all exceptions)
    // =========================================================================

    /**
     * Catch-all handler for any otherwise-unmapped exception.
     *
     * <p>Maps to {@code 500 Internal Server Error} with code {@code "INTERNAL_ERROR"} and a
     * GENERIC client-facing message; internal exception details are never leaked to the
     * client. Logged at {@code ERROR} with the full stack trace (trailing {@code ex}) for
     * diagnostics. Because {@link Exception} is the supertype of every checked and unchecked
     * exception, Spring uses this only when no more specific {@code @ExceptionHandler}
     * matches.</p>
     *
     * @param ex      the unexpected exception
     * @param request the current request, used to populate the error {@code path}
     * @return a {@code 500} {@link ErrorResponse}
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex,
            HttpServletRequest request) {
        log.error("Unexpected error at {}: {}",
                request.getRequestURI(), ex.getMessage(), ex);
        ErrorResponse body = ErrorResponse.builder()
                .status(HttpStatus.INTERNAL_SERVER_ERROR.value())
                .code("INTERNAL_ERROR")
                .message("An unexpected error occurred")
                .path(request.getRequestURI())
                .timestamp(LocalDateTime.now())
                .build();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
