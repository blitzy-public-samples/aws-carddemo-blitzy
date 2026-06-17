package com.carddemo.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unchecked exception that signals a <strong>programmatic / domain input
 * validation</strong> failure within the CardDemo application.
 *
 * <p><strong>HTTP mapping.</strong> This exception is intentionally framework
 * agnostic: it carries <em>no</em> Spring or Jakarta annotations and does
 * <em>not</em> declare {@code @ResponseStatus}. The translation to
 * <strong>HTTP 400 Bad Request</strong> is owned exclusively by
 * {@code GlobalExceptionHandler} through an
 * {@code @ExceptionHandler(ValidationException.class)} method, keeping the HTTP
 * contract in a single place and this type reusable from non-web contexts
 * (e.g. Spring Batch jobs).</p>
 *
 * <p><strong>COBOL lineage.</strong> In the legacy mainframe system, generic
 * input errors were surfaced to the 3270 terminal via the
 * {@code CCDA-MSG-INVALID-KEY} field ("Invalid key pressed. Please see
 * below...") of the {@code CSMSG01Y} message copybook. The online programs
 * performed field-by-field validation inline and re-displayed the map with an
 * error message. This exception is the Java re-expression of that
 * generic input-error pathway: services throw it when caller-supplied data
 * fails a business / format rule, and the global handler renders a structured
 * JSON error payload in place of the re-sent BMS screen.</p>
 *
 * <p><strong>Scope &mdash; domain validation, not framework bean validation.</strong>
 * This type represents validation that application code performs
 * <em>imperatively</em>. The most prominent producer is
 * {@code DateValidationService} (the migration of the {@code CSUTLDTC} date
 * utility), which throws it for malformed or impossible dates &mdash; bad
 * format, a month outside {@code 01-12}, or an invalid leap-year day. Other
 * services throw it for business-input checks that the original COBOL performed
 * inline. By contrast, annotation-driven Bean Validation failures arrive as
 * {@code org.springframework.web.bind.MethodArgumentNotValidException} or
 * {@code jakarta.validation.ConstraintViolationException} and are handled by
 * <em>separate</em> {@code @ExceptionHandler} methods; they do not flow through
 * this class.</p>
 *
 * <p><strong>Optional per-field detail.</strong> Callers may attach a map of
 * <em>field name &rarr; human-readable reason</em> so the API consumer learns
 * exactly which input was rejected and why. The map is defensively copied into
 * an unmodifiable {@link java.util.LinkedHashMap} on construction, preserving
 * insertion order for stable, deterministic error rendering. The accessor
 * {@link #getFieldErrors()} <em>never</em> returns {@code null}; when no
 * per-field detail is supplied it returns an empty map. The global handler may
 * copy {@link #getFieldErrors()} into its {@code ErrorResponse.fieldErrors}.</p>
 *
 * <p><strong>PII suppression (AAP &sect;0.6.8).</strong> Neither the exception
 * message nor any value in the field-error map may echo sensitive data &mdash;
 * specifically a card verification value (CVV), a full Social Security Number
 * (SSN), or any password. The field-error map is designed to carry the field
 * <em>name</em> together with a human-readable <em>reason</em>; it must never
 * carry the raw sensitive <em>value</em> that failed validation. Producers are
 * responsible for honoring this contract when constructing messages and
 * reasons, since these strings ultimately reach API responses and logs.</p>
 *
 * <p>This exception is <em>unchecked</em> ({@code extends RuntimeException}) so
 * that the layered service code need not declare it on every method signature,
 * mirroring the non-local error handling of the original transaction
 * programs.</p>
 *
 * @see RuntimeException
 */
public class ValidationException extends RuntimeException {

    /**
     * Serialization version identifier. Fixed at {@code 1L} because the logical
     * shape of this exception is stable; bump only on an incompatible change.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Immutable, never-{@code null} map of {@code field name -> reason}. Empty
     * when the failure is not attributable to specific named fields. Insertion
     * order is preserved for deterministic rendering.
     */
    private final Map<String, String> fieldErrors;

    /**
     * Creates a validation exception with a human-readable message and no
     * per-field detail.
     *
     * @param message the validation error description; must remain free of PII
     *                (no CVV, full SSN, or password values)
     */
    public ValidationException(String message) {
        super(message);
        this.fieldErrors = Collections.emptyMap();
    }

    /**
     * Creates a validation exception with a human-readable message and the
     * underlying cause that triggered it (for example a
     * {@link java.time.format.DateTimeParseException} raised while parsing a
     * malformed date in {@code DateValidationService}).
     *
     * @param message the validation error description; must remain free of PII
     * @param cause   the underlying cause, may be {@code null}
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
        this.fieldErrors = Collections.emptyMap();
    }

    /**
     * Creates a validation exception with a human-readable message and a map of
     * per-field errors. The supplied map is defensively copied; subsequent
     * mutation of the caller's map does not affect this exception, and the map
     * returned by {@link #getFieldErrors()} is unmodifiable.
     *
     * @param message     the validation error description; must remain free of PII
     * @param fieldErrors map of {@code field name -> human-readable reason};
     *                    may be {@code null} or empty. Values must remain free
     *                    of PII (carry the field name and reason, never the raw
     *                    sensitive value)
     */
    public ValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = defensiveCopy(fieldErrors);
    }

    /**
     * Creates a validation exception with a human-readable message, a map of
     * per-field errors, and the underlying cause. Combines the behavior of the
     * other constructors so callers can report field detail while preserving
     * the original cause for diagnostics.
     *
     * @param message     the validation error description; must remain free of PII
     * @param fieldErrors map of {@code field name -> human-readable reason};
     *                    may be {@code null} or empty. Values must remain free of PII
     * @param cause       the underlying cause, may be {@code null}
     */
    public ValidationException(String message, Map<String, String> fieldErrors, Throwable cause) {
        super(message, cause);
        this.fieldErrors = defensiveCopy(fieldErrors);
    }

    /**
     * Returns the per-field validation errors as an unmodifiable map of
     * {@code field name -> reason}. Never returns {@code null}; an empty map is
     * returned when no per-field detail was supplied.
     *
     * @return an unmodifiable, never-{@code null} map of field errors
     */
    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }

    /**
     * Convenience predicate indicating whether any per-field error detail is
     * present. Lets the global exception handler decide whether to include a
     * {@code fieldErrors} section in the response payload.
     *
     * @return {@code true} if at least one field error is present, otherwise
     *         {@code false}
     */
    public boolean hasFieldErrors() {
        return !fieldErrors.isEmpty();
    }

    /**
     * Produces an unmodifiable, order-preserving copy of the supplied map,
     * normalizing {@code null} and empty inputs to a shared empty map so the
     * {@link #fieldErrors} field is always non-{@code null}.
     *
     * @param source the caller-supplied map, possibly {@code null} or empty
     * @return an unmodifiable copy, or an empty map when {@code source} is
     *         {@code null} or empty
     */
    private static Map<String, String> defensiveCopy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
