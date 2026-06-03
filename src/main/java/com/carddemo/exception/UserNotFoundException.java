package com.carddemo.exception;

/**
 * Exception signalling that a security-user ({@code USRSEC} / {@code SEC-USER-DATA})
 * lookup by user id returned no record.
 *
 * <p>This is the user-administration analogue of {@link AccountNotFoundException}. It is
 * raised by {@code UserService} when an admin operation
 * ({@code GET}/{@code PUT}/{@code DELETE} on {@code /api/admin/users/{userId}}) references
 * a user id that does not exist in the {@code users} table. It mirrors the
 * {@code NOTFND}/{@code INVALID KEY} branches of the original COBOL user-administration
 * programs {@code COUSR01C}/{@code COUSR02C}/{@code COUSR03C}
 * (for example {@code app/cbl/COUSR02C.cbl} paragraph {@code 9000-READ-USER}, which moves
 * {@code 'User ID NOT found...'} into the screen message when the
 * {@code READ USRSEC ... INVALID KEY} branch is taken).</p>
 *
 * <h2>Why a dedicated exception (QA finding remediation)</h2>
 * <p>{@code UserService} previously reused {@link AccountNotFoundException} for the
 * user-not-found path. That produced an account-domain error payload
 * ({@code code=101}, {@code message="Account not found: <id>"}) for what is really a
 * <em>user</em>-administration failure, which is misleading to API consumers and to
 * operators reading logs. This dedicated type carries a user-domain code
 * ({@link #CODE}) and a user-domain message ({@code "User not found: <userId>"}) so the
 * REST contract is accurate, without disturbing the account-validation code hierarchy
 * (COBOL codes 100/101/102/103) that {@link AccountNotFoundException} belongs to.</p>
 *
 * <h2>Inheritance</h2>
 * <p>Extends {@link RuntimeException} <strong>directly</strong> &mdash; <em>not</em>
 * {@link TransactionValidationException} &mdash; for the same reason as
 * {@link DiscloseGroupNotFoundException}: a missing user record is an
 * administrative/reference lookup miss, not a {@code CBTRN02C} per-transaction validation
 * rule, so it deliberately carries <strong>no</strong> numeric COBOL reason code. Instead
 * it exposes the symbolic {@link #CODE} constant {@code "USER_NOT_FOUND"}, which
 * {@code GlobalExceptionHandler} surfaces on the {@code ErrorResponse.code} field.</p>
 *
 * <p>It is an <em>unchecked</em> exception so that service code need not declare it,
 * mirroring the non-recoverable "set message and return to the user" flow of the original
 * COBOL programs.</p>
 *
 * <h2>HTTP mapping</h2>
 * <p>Handled by {@code GlobalExceptionHandler} ({@code @RestControllerAdvice}) and mapped
 * to {@code 404 Not Found} with {@code code="USER_NOT_FOUND"}.</p>
 *
 * @see AccountNotFoundException
 * @see DiscloseGroupNotFoundException
 */
public class UserNotFoundException extends RuntimeException {

    /**
     * Serialization version identifier for the {@link java.io.Serializable} contract
     * inherited from {@link Throwable}.
     */
    private static final long serialVersionUID = 1L;

    /**
     * Symbolic error code surfaced on the {@code ErrorResponse.code} field by
     * {@code GlobalExceptionHandler}. Deliberately a user-domain symbolic value rather
     * than the account-domain numeric COBOL code {@code 101}, so that user-not-found and
     * account-not-found are distinguishable by API consumers.
     */
    public static final String CODE = "USER_NOT_FOUND";

    /**
     * Primary constructor &mdash; produces a user-domain message embedding the missing
     * user id, of the form {@code "User not found: <userId>"}.
     *
     * <p>A {@code null} argument is permitted; it renders as the literal text
     * {@code "null"} (i.e. {@code "User not found: null"}), which is an acceptable,
     * non-failing diagnostic message.</p>
     *
     * @param userId the user id that was not found (nullable)
     */
    public UserNotFoundException(String userId) {
        super("User not found: " + userId);
    }

    /**
     * Constructor with message and cause &mdash; for exception chaining when the miss is
     * wrapping an underlying repository, JPA or framework error that surfaced during the
     * user lookup.
     *
     * <p>The supplied message is used <em>verbatim</em> (no {@code "User not found: "}
     * prefix is applied) so callers retain full control of the surfaced text when they
     * need to attach a cause.</p>
     *
     * @param message the exact failure message to expose via {@link #getMessage()}
     * @param cause   the underlying cause (saved for later retrieval by
     *                {@link Throwable#getCause()}); a {@code null} value is permitted and
     *                indicates that the cause is nonexistent or unknown
     */
    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
