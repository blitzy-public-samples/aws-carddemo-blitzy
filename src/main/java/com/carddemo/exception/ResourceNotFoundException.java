package com.carddemo.exception;

/**
 * Signals that a requested domain entity does not exist.
 *
 * <p>This is the generic "entity not found" carrier for the CardDemo
 * COBOL&#8594;Java migration. It replaces the scattered "record/entity not
 * found" screen messages emitted by the legacy online programs (for example,
 * the "User not found" path in {@code COSGN00C} and the "... not found" paths
 * in {@code COACTVWC} and {@code COTRN01C}) and the legacy message copybooks
 * (such as {@code app/cpy/CSMSG01Y.cpy}). Service and repository layers throw
 * this exception whenever a lookup by primary key or business key yields no
 * row.</p>
 *
 * <p><strong>HTTP semantics.</strong> This class is intentionally
 * framework-free. The mapping to <em>HTTP&nbsp;404&nbsp;Not&nbsp;Found</em> is
 * owned <em>exclusively</em> by {@code GlobalExceptionHandler} via
 * {@code @ExceptionHandler(ResourceNotFoundException.class)}, which builds the
 * shared {@code ErrorResponse} body. Therefore this class carries
 * <em>no</em> {@code @ResponseStatus} annotation and <em>no</em> Spring or
 * Jakarta imports, keeping it independent of the web tier.</p>
 *
 * <p><strong>Unchecked by design.</strong> It extends {@link RuntimeException}
 * so that services and repositories may throw it without {@code throws}
 * clauses, and so that Spring's transaction manager performs a rollback by
 * default when it propagates out of a {@code @Transactional} boundary.</p>
 *
 * <p><strong>PII suppression (AAP &sect;0.6.8).</strong> Callers MUST NOT place
 * sensitive data &mdash; card CVV, full Social Security Number (SSN), or
 * passwords &mdash; into the message or the identifier. Non-sensitive business
 * identifiers such as account ids, card ids, and customer ids are acceptable.</p>
 *
 * <p><strong>Usage.</strong> Prefer the {@link #of(String, Object)} factory for
 * a consistent, neutral message format, for example:</p>
 * <pre>{@code
 *     throw ResourceNotFoundException.of("Account", accountId);
 *     // -> "Account not found with id: 00000000010"
 * }</pre>
 */
public class ResourceNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier. {@link RuntimeException} is
     * {@link java.io.Serializable} through {@link Throwable}, so an explicit,
     * stable {@code serialVersionUID} is declared to guard against
     * incompatible-class warnings across JVMs and builds.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The logical type of the entity that was not found (for example
     * {@code "Account"}, {@code "Card"}, {@code "Customer"}, {@code "User"}).
     * May be {@code null} when the exception is constructed from a free-form
     * message rather than via {@link #of(String, Object)}.
     */
    private final String resourceType;

    /**
     * The business identifier that was searched for (for example an account id
     * or card id). Declared {@code transient} because an arbitrary
     * {@link Object} identifier is not guaranteed to be
     * {@link java.io.Serializable}; the human-readable message (preserved by
     * {@link Throwable}) remains the authoritative record after
     * deserialization. May be {@code null}.
     *
     * <p>Never place sensitive data (CVV, full SSN, passwords) here.</p>
     */
    private final transient Object identifier;

    /**
     * Creates an exception with a fully formed, human-readable message.
     *
     * @param message the detail message describing the missing entity; must not
     *                contain sensitive data (CVV, full SSN, passwords)
     */
    public ResourceNotFoundException(String message) {
        super(message);
        this.resourceType = null;
        this.identifier = null;
    }

    /**
     * Creates an exception with a message and an underlying cause, preserving
     * the original stack trace for diagnostics.
     *
     * @param message the detail message describing the missing entity; must not
     *                contain sensitive data (CVV, full SSN, passwords)
     * @param cause   the underlying cause (a {@code null} value is permitted and
     *                indicates that the cause is nonexistent or unknown)
     */
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.resourceType = null;
        this.identifier = null;
    }

    /**
     * Internal constructor backing {@link #of(String, Object)}. It composes the
     * standardized detail message and retains the structured metadata for
     * richer logging.
     *
     * <p>This constructor is {@code private} and takes {@code (String, Object)};
     * it never collides with the public {@code (String, Throwable)} constructor
     * during overload resolution because {@link Object} is not assignable to
     * {@link Throwable}.</p>
     *
     * @param resourceType the logical entity type, used both in the message and
     *                     retained for logging
     * @param identifier   the business identifier searched for, used both in the
     *                     message and retained for logging
     */
    private ResourceNotFoundException(String resourceType, Object identifier) {
        super(resourceType + " not found with id: " + identifier);
        this.resourceType = resourceType;
        this.identifier = identifier;
    }

    /**
     * Convenience factory producing a standardized "&lt;type&gt; not found with
     * id: &lt;identifier&gt;" message, mirroring the legacy "not found" screen
     * text consistently across services.
     *
     * <p>Example: {@code of("Account", 10L)} yields the message
     * {@code "Account not found with id: 10"}.</p>
     *
     * <p>The {@code resourceType} and {@code identifier} are also retained on the
     * exception (see {@link #getResourceType()} and {@link #getIdentifier()}) for
     * structured logging. Do not pass sensitive data (CVV, full SSN, passwords);
     * non-sensitive business identifiers (account/card/customer ids) only.</p>
     *
     * @param resourceType the logical entity type (for example {@code "Account"})
     * @param identifier   the non-sensitive business identifier that was searched
     *                     for; its {@code String} representation is appended to the
     *                     message
     * @return a new {@code ResourceNotFoundException} carrying the standardized
     *         message and the supplied metadata
     */
    public static ResourceNotFoundException of(String resourceType, Object identifier) {
        return new ResourceNotFoundException(resourceType, identifier);
    }

    /**
     * Returns the logical entity type that was not found, or {@code null} if this
     * exception was created from a free-form message rather than via
     * {@link #of(String, Object)}.
     *
     * @return the resource type, or {@code null}
     */
    public String getResourceType() {
        return resourceType;
    }

    /**
     * Returns the business identifier that was searched for, or {@code null} if
     * this exception was created from a free-form message or restored via
     * deserialization (the identifier is {@code transient}).
     *
     * @return the identifier, or {@code null}
     */
    public Object getIdentifier() {
        return identifier;
    }
}
