package com.carddemo.exception;

/**
 * Domain exception that signals an <strong>optimistic-locking (lost-update) conflict</strong>
 * detected while persisting a CardDemo aggregate (most notably an {@code Account}, but applicable
 * to any {@code @Version}-guarded entity).
 *
 * <h2>HTTP semantics</h2>
 * This exception is the application-layer <em>signal</em> for a concurrent-modification conflict.
 * The central {@code GlobalExceptionHandler} (a {@code @RestControllerAdvice} living in this same
 * package) maps it to <strong>HTTP 409 Conflict</strong>. The handler maps two distinct conditions
 * to the same 409 status:
 * <ul>
 *   <li>this domain {@code ConcurrentModificationException}, thrown explicitly by the service layer
 *       after an application-level change-detection check; and</li>
 *   <li>Spring's {@code org.springframework.orm.ObjectOptimisticLockingFailureException}, raised
 *       automatically by Hibernate on flush when a {@code @Version} mismatch is detected.</li>
 * </ul>
 * Deliberately, this class carries <strong>no</strong> {@code @ResponseStatus} annotation and no
 * Spring/Jakarta dependency: the HTTP mapping is owned entirely by {@code GlobalExceptionHandler},
 * keeping this type framework-free and reusable from any tier (including plain unit tests).
 *
 * <h2>Mainframe parity lineage (AAP &sect;0.6.6)</h2>
 * In the legacy CardDemo system the "lost update" hazard was guarded inside the online update
 * programs:
 * <ul>
 *   <li><code>COACTUPC</code> (account update) issued a CICS <code>READ ... UPDATE</code> followed by
 *       a <code>REWRITE</code> and inspected the VSAM file-status code to detect a record that had
 *       changed underneath it; and</li>
 *   <li><code>COCRDUPC</code> (card update) performed explicit change-detection via its
 *       <code>WS-DATACHANGED-FLAG</code> before rewriting.</li>
 * </ul>
 * Those guards are reproduced in the Spring Boot monolith by a JPA {@code @Version} optimistic-lock
 * check. When a collision occurs, the service/repository layer (owned by other components) either
 * lets Hibernate raise its optimistic-locking failure on flush, or throws this domain exception
 * after an explicit change-detection comparison. Both outcomes converge on a single 409 response,
 * preserving the original "your update was rejected because the record changed; reload and retry"
 * behavior. <strong>This class does not itself perform any locking or change-detection check &mdash;
 * it is purely the signal that such a check failed.</strong>
 *
 * <h2>&#9888; Name-clash warning ({@code java.util})</h2>
 * The simple name {@code ConcurrentModificationException} intentionally matches the JDK's
 * {@code java.util.ConcurrentModificationException}. <strong>This</strong> type is the CardDemo
 * domain exception declared in package {@code com.carddemo.exception} and is the one that must be
 * thrown and handled for optimistic-locking conflicts. In any file that uses this class, do
 * <strong>not</strong> add {@code import java.util.ConcurrentModificationException;} &mdash; doing so
 * would silently shadow this domain type with the unrelated JDK collection-iteration exception.
 * Because {@code GlobalExceptionHandler} resides in this same package, it references this class with
 * no import at all, which is correct and unambiguous. From a different package, reference it by its
 * fully-qualified name {@code com.carddemo.exception.ConcurrentModificationException} rather than
 * importing the {@code java.util} variant.
 *
 * <h2>PII suppression (AAP &sect;0.6.8)</h2>
 * Messages carried by this exception must never embed sensitive data &mdash; no card verification
 * value (CVV), no full Social Security Number (SSN), and no passwords. Callers should supply a
 * neutral, user-safe description (or rely on {@link #ConcurrentModificationException() the default
 * message}); identifying context for diagnostics belongs in server-side logs, never in the
 * client-facing 409 payload.
 */
public class ConcurrentModificationException extends RuntimeException {

    /**
     * Serialization version identifier. Declared explicitly so the serialized form remains stable
     * across builds, in line with {@link java.io.Serializable} best practice for {@link Throwable}
     * subclasses.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Neutral, PII-free default message used by the no-argument constructor to standardize the 409
     * response text. Kept {@code private} because the application's user-facing message catalog is
     * owned by the dedicated message/error subsystem (the {@code MessageService} that supersedes the
     * legacy {@code CSMSG01Y} copybook), not by individual exception types.
     */
    private static final String DEFAULT_MESSAGE =
            "The record was modified by another user. Please reload and retry.";

    /**
     * Creates the exception with the standardized, neutral default message
     * (&quot;{@value #DEFAULT_MESSAGE}&quot;). Convenient for the common case where the service layer
     * detects a conflict and wants a consistent, user-safe 409 description without composing its own
     * text.
     */
    public ConcurrentModificationException() {
        super(DEFAULT_MESSAGE);
    }

    /**
     * Creates the exception with a caller-supplied detail message.
     *
     * @param message a neutral, user-safe description of the optimistic-locking conflict; must not
     *                contain PII (no CVV, full SSN, or passwords) per AAP &sect;0.6.8
     */
    public ConcurrentModificationException(String message) {
        super(message);
    }

    /**
     * Creates the exception with a caller-supplied detail message and an underlying cause.
     *
     * <p>The {@code cause} overload exists so the service layer can wrap a caught Spring
     * {@code org.springframework.orm.ObjectOptimisticLockingFailureException} (or any other
     * lower-level locking failure) while preserving the original stack trace for server-side
     * logging and diagnostics. The wrapped cause is retained internally and is <em>not</em> exposed
     * in the client-facing 409 payload.
     *
     * @param message a neutral, user-safe description of the optimistic-locking conflict; must not
     *                contain PII (no CVV, full SSN, or passwords) per AAP &sect;0.6.8
     * @param cause   the underlying throwable that triggered this conflict (for example, a JPA
     *                optimistic-locking failure); may be {@code null}
     */
    public ConcurrentModificationException(String message, Throwable cause) {
        super(message, cause);
    }
}
