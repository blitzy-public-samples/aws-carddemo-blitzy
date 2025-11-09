/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.exception;

/**
 * Custom unchecked exception for authentication and authorization failures in the CardDemo application.
 * 
 * <p>This exception replaces COBOL RESP-CD error handling patterns from the mainframe COSGN00C.cbl
 * authentication program, providing equivalent error semantics for modern Spring Boot REST APIs.</p>
 * 
 * <h2>COBOL Origin - Error Pattern Mapping</h2>
 * <p>This exception class transforms the following COBOL error handling patterns:</p>
 * <ul>
 *   <li><b>RESP-CD 13 "User not found"</b> (COSGN00C.cbl lines 247-251): Thrown when user ID 
 *       lookup fails in the USRSEC file, now handled via UserRepository.findByUserId()</li>
 *   <li><b>Password mismatch "Wrong Password. Try again"</b> (lines 241-246): Thrown when 
 *       password validation fails during authentication</li>
 *   <li><b>General authentication failure "Unable to verify the User"</b> (lines 252-257): 
 *       Thrown for any other authentication system errors</li>
 * </ul>
 * 
 * <h2>Usage Scenarios</h2>
 * <p>This exception is thrown in the following scenarios:</p>
 * <ul>
 *   <li><b>Invalid Credentials</b>: User ID not found or password does not match</li>
 *   <li><b>Expired Sessions</b>: User session has timed out or been invalidated</li>
 *   <li><b>Invalid JWT Tokens</b>: JWT token signature invalid, expired, or malformed</li>
 *   <li><b>Missing Authentication</b>: Required authentication header missing from request</li>
 *   <li><b>Unauthorized Access</b>: User lacks required role or permissions (authorization failure)</li>
 * </ul>
 * 
 * <h2>Integration Points</h2>
 * <p>This exception is used by:</p>
 * <ul>
 *   <li><b>AuthenticationService</b>: Thrown during login validation when credentials are invalid</li>
 *   <li><b>JwtAuthenticationFilter</b>: Thrown when JWT token validation fails</li>
 *   <li><b>Service Methods with @PreAuthorize</b>: Thrown when role-based access control fails</li>
 *   <li><b>GlobalExceptionHandler</b>: Catches this exception and transforms it into 
 *       HTTP 401 Unauthorized responses with appropriate error details</li>
 * </ul>
 * 
 * <h2>Spring Security Integration</h2>
 * <p>As an unchecked exception extending {@link RuntimeException}, this exception integrates
 * seamlessly with Spring's exception handling infrastructure:</p>
 * <ul>
 *   <li>Works with {@code @ControllerAdvice} for centralized exception handling</li>
 *   <li>Triggers automatic rollback in {@code @Transactional} methods</li>
 *   <li>Does not require explicit {@code throws} declarations in method signatures</li>
 *   <li>Matches COBOL RESP-CD patterns that interrupt normal program flow</li>
 * </ul>
 * 
 * <h2>HTTP Response Mapping</h2>
 * <p>When caught by GlobalExceptionHandler, this exception maps to:</p>
 * <ul>
 *   <li><b>HTTP Status</b>: 401 Unauthorized</li>
 *   <li><b>Response Body</b>: JSON error object with message, timestamp, and path</li>
 *   <li><b>Error Semantics</b>: Preserves original mainframe error message meaning</li>
 * </ul>
 * 
 * <h2>Distinction: Authentication vs Authorization</h2>
 * <p>This exception handles both authentication and authorization failures:</p>
 * <ul>
 *   <li><b>Authentication Failure</b>: User identity cannot be verified (invalid credentials)</li>
 *   <li><b>Authorization Failure</b>: User identity verified but lacks required privileges</li>
 * </ul>
 * <p>Both scenarios result in HTTP 401 responses to maintain security best practices
 * (not revealing whether user exists or simply lacks permissions).</p>
 * 
 * <h2>Example Usage</h2>
 * <pre>{@code
 * // In AuthenticationService - Invalid credentials
 * User user = userRepository.findByUserId(userId)
 *     .orElseThrow(() -> new AuthenticationException("User not found. Try again ..."));
 * 
 * if (!passwordEncoder.matches(password, user.getPassword())) {
 *     throw new AuthenticationException("Wrong Password. Try again ...");
 * }
 * 
 * // In JwtAuthenticationFilter - Token validation failure
 * if (!jwtService.validateToken(token)) {
 *     throw new AuthenticationException("Invalid or expired JWT token");
 * }
 * 
 * // In Service methods - Authorization failure
 * @PreAuthorize("hasRole('ADMIN')")
 * public void adminOnlyMethod() {
 *     // If user lacks ADMIN role, Spring Security throws AccessDeniedException
 *     // which can be wrapped as AuthenticationException in GlobalExceptionHandler
 * }
 * }</pre>
 * 
 * <h2>Thread Safety</h2>
 * <p>This exception is immutable and thread-safe. Exception instances can be safely
 * shared across threads if needed (though typically new instances are created per error).</p>
 * 
 * <h2>Serialization</h2>
 * <p>As a subclass of {@link RuntimeException}, this exception is serializable and can
 * be transmitted across JVM boundaries if needed for distributed tracing or logging.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 * 
 * @see RuntimeException
 * @see org.springframework.security.core.AuthenticationException
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see com.carddemo.service.auth.AuthenticationService
 * @see com.carddemo.security.JwtAuthenticationFilter
 */
public class AuthenticationException extends RuntimeException {

    /**
     * Serial version UID for serialization compatibility.
     * 
     * <p>This UID ensures that serialized instances of this exception can be
     * deserialized correctly across different versions of the application.</p>
     */
    private static final long serialVersionUID = 1L;

    /**
     * Constructs a new authentication exception with the specified detail message.
     * 
     * <p>This constructor is used when a simple error message is sufficient to
     * describe the authentication failure. The message should be meaningful to
     * help diagnose the authentication issue while being safe to display to users.</p>
     * 
     * <h3>Usage Guidelines</h3>
     * <ul>
     *   <li>Preserve COBOL error message semantics when applicable</li>
     *   <li>Use clear, user-friendly messages (e.g., "Invalid credentials")</li>
     *   <li>Avoid revealing sensitive information (e.g., whether user exists)</li>
     *   <li>Include context that helps with troubleshooting in logs</li>
     * </ul>
     * 
     * <h3>Common Message Patterns</h3>
     * <ul>
     *   <li>"User not found. Try again ..." - COBOL RESP-CD 13 equivalent</li>
     *   <li>"Wrong Password. Try again ..." - COBOL password mismatch equivalent</li>
     *   <li>"Unable to verify the User ..." - COBOL general auth failure equivalent</li>
     *   <li>"Invalid or expired JWT token" - Modern JWT validation failure</li>
     *   <li>"Missing authentication header" - Modern REST API auth failure</li>
     *   <li>"Insufficient privileges" - Authorization failure</li>
     * </ul>
     * 
     * <h3>Example Usage</h3>
     * <pre>{@code
     * // Invalid credentials scenario
     * throw new AuthenticationException("User not found. Try again ...");
     * 
     * // Password mismatch scenario
     * throw new AuthenticationException("Wrong Password. Try again ...");
     * 
     * // JWT token validation failure
     * throw new AuthenticationException("Invalid or expired JWT token");
     * 
     * // Missing authentication
     * throw new AuthenticationException("Authentication required to access this resource");
     * }</pre>
     * 
     * @param message the detail message explaining the authentication failure.
     *                The message is saved for later retrieval by the {@link #getMessage()} method.
     *                Should not be null, though null is technically allowed by the superclass.
     *                
     * @see RuntimeException#RuntimeException(String)
     * @see #getMessage()
     */
    public AuthenticationException(String message) {
        super(message);
    }

    /**
     * Constructs a new authentication exception with the specified detail message and cause.
     * 
     * <p>This constructor is used when the authentication failure is caused by another
     * exception that should be preserved in the stack trace. This is particularly useful
     * for wrapping lower-level exceptions (database exceptions, network errors, etc.)
     * while providing a clear authentication-specific error message.</p>
     * 
     * <h3>Usage Guidelines</h3>
     * <ul>
     *   <li>Wrap low-level exceptions (SQLException, IOException) with auth context</li>
     *   <li>Preserve original stack trace for debugging and logging</li>
     *   <li>Provide user-friendly message while keeping technical details in cause</li>
     *   <li>Use when authentication failure is due to infrastructure issues</li>
     * </ul>
     * 
     * <h3>Common Cause Exception Types</h3>
     * <ul>
     *   <li><b>JpaException</b>: Database connectivity issues during user lookup</li>
     *   <li><b>DataAccessException</b>: Spring Data errors during authentication</li>
     *   <li><b>SignatureException</b>: JWT signature validation failed</li>
     *   <li><b>ExpiredJwtException</b>: JWT token has expired</li>
     *   <li><b>MalformedJwtException</b>: JWT token format is invalid</li>
     *   <li><b>IllegalArgumentException</b>: Invalid authentication parameters</li>
     * </ul>
     * 
     * <h3>Example Usage</h3>
     * <pre>{@code
     * // Wrapping database exception during user lookup
     * try {
     *     User user = userRepository.findByUserId(userId).orElseThrow();
     * } catch (DataAccessException e) {
     *     throw new AuthenticationException("Unable to verify the User ...", e);
     * }
     * 
     * // Wrapping JWT validation exception
     * try {
     *     Claims claims = jwtParser.parseClaimsJws(token).getBody();
     * } catch (ExpiredJwtException e) {
     *     throw new AuthenticationException("JWT token has expired", e);
     * } catch (SignatureException e) {
     *     throw new AuthenticationException("Invalid JWT token signature", e);
     * }
     * 
     * // Wrapping password encoding exception
     * try {
     *     boolean matches = passwordEncoder.matches(rawPassword, encodedPassword);
     * } catch (IllegalArgumentException e) {
     *     throw new AuthenticationException("Password validation failed", e);
     * }
     * }</pre>
     * 
     * <h3>Cause Chain Handling</h3>
     * <p>The cause exception is preserved and can be retrieved using {@link #getCause()}.
     * This allows GlobalExceptionHandler and logging frameworks to access the complete
     * exception chain for detailed error reporting and troubleshooting.</p>
     * 
     * @param message the detail message explaining the authentication failure.
     *                The message is saved for later retrieval by the {@link #getMessage()} method.
     *                Should provide user-friendly context for the error.
     *                
     * @param cause the cause of the authentication failure (which is saved for later retrieval
     *              by the {@link #getCause()} method). A null value is permitted and indicates
     *              that the cause is nonexistent or unknown. Common causes include database
     *              exceptions, JWT parsing exceptions, or network errors.
     *              
     * @see RuntimeException#RuntimeException(String, Throwable)
     * @see #getCause()
     * @see #getMessage()
     */
    public AuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
