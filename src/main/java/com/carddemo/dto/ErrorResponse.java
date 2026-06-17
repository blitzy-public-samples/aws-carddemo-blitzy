package com.carddemo.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Standardized API error envelope returned for <em>every</em> error condition surfaced by the
 * CardDemo REST API.
 *
 * <p>This is the single, canonical JSON shape emitted by the central
 * {@code com.carddemo.exception.GlobalExceptionHandler} ({@code @RestControllerAdvice}) for the full
 * spectrum of failures &mdash; Bean Validation failures (HTTP 400), authentication failures
 * (HTTP 401), authorization failures (HTTP 403), missing resources (HTTP 404), optimistic-locking /
 * concurrency conflicts (HTTP 409), and unexpected server errors (HTTP 500). Consolidating all error
 * output behind one contract gives API consumers a predictable, machine-parseable error format.</p>
 *
 * <h2>Legacy lineage</h2>
 * <p>In the original mainframe CardDemo application, user-facing and abend (abnormal-end) messaging was
 * scattered across COBOL copybooks &mdash; {@code CSMSG01Y} ({@code CCDA-COMMON-MESSAGES}: human-readable
 * status text such as the "invalid key pressed" prompt) and {@code CSMSG02Y} ({@code ABEND-DATA}: the
 * abend code, culprit program, reason, and message text). This record replaces that scattered,
 * copybook-driven message handling with one structured envelope: the COBOL message text maps to
 * {@link #message()}, while the abend code / reason context maps to the {@link #status()} /
 * {@link #error()} pairing.</p>
 *
 * <h2>JSON serialization contract</h2>
 * <p>The type is annotated with {@link JsonInclude}({@link JsonInclude.Include#NON_NULL NON_NULL}) so
 * that {@code null} components are omitted from the serialized payload entirely. In particular,
 * {@link #fieldErrors()} is absent from the JSON for non-validation errors and is rendered as a JSON
 * array only when per-field validation details are present. The {@link #timestamp()} component
 * serializes to an ISO-8601 string when a {@code JavaTimeModule} is registered on the application's
 * {@code ObjectMapper} (wired globally by {@code com.carddemo.config.JacksonConfig}).</p>
 *
 * <p>Example payload for a validation failure (HTTP 400):</p>
 * <pre>{@code
 * {
 *   "timestamp": "2022-07-19T23:15:58.123",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "message": "Validation failed for request",
 *   "path": "/api/accounts/00000000011",
 *   "fieldErrors": [
 *     { "field": "creditLimit", "message": "must be greater than or equal to 0" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Example payload for a not-found error (HTTP 404), where {@code fieldErrors} is omitted:</p>
 * <pre>{@code
 * {
 *   "timestamp": "2022-07-19T23:15:58.123",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "Account 00000000011 not found",
 *   "path": "/api/accounts/00000000011"
 * }
 * }</pre>
 *
 * <h2>Design constraints</h2>
 * <ul>
 *   <li>This is a Tier-0 payload object: it depends only on the JDK and Jackson annotations and carries
 *       no references to entities, HTTP/Spring exception types, or any other internal package. The
 *       {@code GlobalExceptionHandler} is solely responsible for translating exceptions into this
 *       shape.</li>
 *   <li>The component names and types ({@code timestamp}, {@code status}, {@code error},
 *       {@code message}, {@code path}, {@code fieldErrors}) form a hard, published contract consumed by
 *       {@code GlobalExceptionHandler} and by API clients; they must not be renamed or reordered.
 *       Note that {@code path} is {@link JsonIgnore @JsonIgnore}d &mdash; it is part of the in-process
 *       object contract but is deliberately excluded from the serialized wire contract (Issue 5).</li>
 * </ul>
 *
 * @param timestamp   the moment the error response was generated; serialized as an ISO-8601 string
 * @param status      the numeric HTTP status code (for example {@code 400}, {@code 404}, {@code 409})
 * @param error       the HTTP reason phrase (for example {@code "Bad Request"}, {@code "Conflict"})
 * @param message     a human-readable detail message describing the failure (ported from the
 *                    {@code CSMSG01Y}/{@code CSMSG02Y} style of standardized COBOL messages)
 * @param path        the request URI that produced the error. <strong>Internal-only:</strong>
 *                    annotated {@link JsonIgnore @JsonIgnore} so it is <em>never</em> serialized into
 *                    the outbound JSON error envelope (QA finding: Issue 5 — information disclosure).
 *                    It is retained as a record component purely for server-side use (logging,
 *                    correlation, and unit-test assertions); API clients never receive it. Removing
 *                    it from the wire prevents leaking the resolved request path back to the caller
 *                    while keeping the accessor available in-process.
 * @param fieldErrors per-field validation details; {@code null} (and therefore omitted from JSON) unless
 *                    field-level validation failed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        @JsonIgnore String path,
        List<FieldErrorDetail> fieldErrors
) {

    /**
     * A single field-level validation violation.
     *
     * <p>Deliberately nested inside {@link ErrorResponse} (rather than declared as a separate top-level
     * file) because it has no meaning outside the error envelope. Instances are typically produced by
     * the {@code GlobalExceptionHandler} when handling a Spring
     * {@code MethodArgumentNotValidException}, one entry per violated Bean Validation constraint, so
     * that HTTP 400 responses can enumerate exactly which request fields failed and why.</p>
     *
     * @param field   the name of the request field that failed validation
     * @param message the Bean Validation constraint message describing the violation
     */
    public record FieldErrorDetail(String field, String message) {
    }

    /**
     * Convenience factory for errors that carry no per-field validation details (the common case for
     * 401/403/404/409/500 responses). The {@link #timestamp()} is stamped with the current time and
     * {@link #fieldErrors()} is set to {@code null} (and thus omitted from the JSON payload).
     *
     * @param status  the numeric HTTP status code
     * @param error   the HTTP reason phrase
     * @param message a human-readable detail message
     * @param path    the request URI that produced the error
     * @return a fully populated {@code ErrorResponse} with no field errors
     */
    public static ErrorResponse of(int status, String error, String message, String path) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path, null);
    }

    /**
     * Convenience factory for validation errors that enumerate per-field details (typically HTTP 400).
     * The {@link #timestamp()} is stamped with the current time.
     *
     * @param status      the numeric HTTP status code
     * @param error       the HTTP reason phrase
     * @param message     a human-readable detail message
     * @param path        the request URI that produced the error
     * @param fieldErrors the per-field validation violations; may be {@code null} or empty
     * @return a fully populated {@code ErrorResponse} including the supplied field errors
     */
    public static ErrorResponse of(int status, String error, String message, String path,
                                   List<FieldErrorDetail> fieldErrors) {
        return new ErrorResponse(LocalDateTime.now(), status, error, message, path, fieldErrors);
    }
}
