package com.carddemo.exception;

/**
 * Custom runtime exception thrown when user lookup operations fail to find the 
 * requested user in the database.
 * 
 * <p>This exception represents scenarios where COBOL mainframe programs encounter
 * user not found conditions, specifically:</p>
 * <ul>
 *   <li>EXEC CICS READ USRSEC operations returning DFHRESP(NOTFND) response code (13)</li>
 *   <li>COBOL file-status 23 (record not found) from USRSEC file operations</li>
 *   <li>Mainframe programs: COSGN00C.cbl (lines 247-251), COUSR00C.cbl, COUSR01C.cbl, COUSR02C.cbl</li>
 * </ul>
 * 
 * <p>Indicates that a user with the specified user ID does not exist in the 
 * USRSEC entity (PostgreSQL user_security table).</p>
 * 
 * <p>Carries contextual information about the failed lookup including the user 
 * identifier used in the search. Enables precise error handling and appropriate 
 * HTTP 404 NOT FOUND responses to REST API clients when user resources cannot be located.</p>
 * 
 * <p>This exception is thread-safe and immutable, suitable for concurrent operations
 * and integrates with Spring @Transactional rollback on user update/delete failures.</p>
 * 
 * <p><strong>Mainframe Equivalence:</strong></p>
 * <pre>
 * COBOL (COSGN00C.cbl lines 247-251):
 *     WHEN 13
 *         MOVE 'Y'      TO WS-ERR-FLG
 *         MOVE 'User not found. Try again ...' TO WS-MESSAGE
 *         MOVE -1       TO USERIDL OF COSGN0AI
 *         PERFORM SEND-SIGNON-SCREEN
 * 
 * Java Spring Boot Equivalent:
 *     throw new UserNotFoundException(userId);
 *     // Caught by GlobalExceptionHandler
 *     // Returns HTTP 404 NOT_FOUND with userId context
 * </pre>
 * 
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see com.carddemo.entity.UserSecurity
 * @since CardDemo Java Migration v1.0
 */
public class UserNotFoundException extends RuntimeException {

    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The user identifier that was not found in the database.
     * This field is immutable and thread-safe (final).
     */
    private final String userId;

    /**
     * Constructs a new UserNotFoundException with the specified user identifier.
     * Uses a default error message.
     * 
     * <p>This constructor is typically used when only the user ID context is needed
     * and a standard error message is sufficient.</p>
     * 
     * @param userId the user identifier that was not found (must not be null)
     * @throws IllegalArgumentException if userId is null
     */
    public UserNotFoundException(String userId) {
        super(buildDefaultMessage(userId));
        validateUserId(userId);
        this.userId = userId;
    }

    /**
     * Constructs a new UserNotFoundException with a custom message and user identifier.
     * 
     * <p>This constructor allows for detailed error messages while preserving
     * the user ID context for error handling and logging.</p>
     * 
     * @param message the detailed error message
     * @param userId the user identifier that was not found (must not be null)
     * @throws IllegalArgumentException if userId is null
     */
    public UserNotFoundException(String message, String userId) {
        super(message);
        validateUserId(userId);
        this.userId = userId;
    }

    /**
     * Constructs a new UserNotFoundException with a custom message and root cause.
     * 
     * <p>This constructor is used when the user not found condition is triggered
     * by an underlying exception (e.g., database access error). The userId is
     * extracted from the message if possible, or set to null.</p>
     * 
     * <p><strong>Note:</strong> When using this constructor, userId is not explicitly
     * provided. If userId context is critical, use 
     * {@link #UserNotFoundException(String, String, Throwable)} instead.</p>
     * 
     * @param message the detailed error message
     * @param cause the underlying cause of this exception
     */
    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.userId = null; // No explicit userId provided in this constructor
    }

    /**
     * Constructs a new UserNotFoundException with a custom message, user identifier,
     * and root cause.
     * 
     * <p>This is the most comprehensive constructor, providing full context about
     * the exception including the user ID, detailed message, and underlying cause.
     * Preferred when chaining exceptions during database access failures.</p>
     * 
     * @param message the detailed error message
     * @param userId the user identifier that was not found (must not be null)
     * @param cause the underlying cause of this exception
     * @throws IllegalArgumentException if userId is null
     */
    public UserNotFoundException(String message, String userId, Throwable cause) {
        super(message, cause);
        validateUserId(userId);
        this.userId = userId;
    }

    /**
     * Returns the user identifier that was not found.
     * 
     * <p>This method provides access to the user ID context, enabling
     * GlobalExceptionHandler to include this information in HTTP 404 error
     * responses for client debugging and audit trail purposes.</p>
     * 
     * @return the user identifier, or null if not provided during construction
     */
    public String getUserId() {
        return userId;
    }

    /**
     * Returns the detail message string of this exception.
     * 
     * <p>Overrides the parent getMessage() to ensure consistent error message
     * formatting. If a custom message was provided, it is returned as-is.
     * Otherwise, a default message including the userId is generated.</p>
     * 
     * @return the detail message string
     */
    @Override
    public String getMessage() {
        String message = super.getMessage();
        if (message == null && userId != null) {
            return buildDefaultMessage(userId);
        }
        return message;
    }

    /**
     * Builds a default error message for the specified user identifier.
     * 
     * <p>Mirrors the COBOL error message from COSGN00C.cbl line 249:
     * "User not found. Try again ..."</p>
     * 
     * @param userId the user identifier
     * @return formatted error message
     */
    private static String buildDefaultMessage(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return "User not found";
        }
        return String.format("User not found with ID: %s", userId);
    }

    /**
     * Validates that the userId parameter is not null.
     * 
     * <p>Ensures immutability and prevents null pointer exceptions during
     * exception handling. This validation maintains data integrity consistent
     * with COBOL's requirement for valid user IDs.</p>
     * 
     * @param userId the user identifier to validate
     * @throws IllegalArgumentException if userId is null
     */
    private static void validateUserId(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
    }
}
