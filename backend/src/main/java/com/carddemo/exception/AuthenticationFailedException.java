/*
 * AuthenticationFailedException.java
 * CardDemo - Authentication Exception Handler
 * 
 * Custom authentication exception for user authentication and authorization failures
 * during login and security operations. Maps to COBOL USRSEC file validation errors
 * in COSGN00C.cbl sign-on program.
 * 
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.carddemo.exception;

import org.springframework.security.core.AuthenticationException;

/**
 * Custom exception thrown when user authentication fails.
 * 
 * This exception extends Spring Security's AuthenticationException to integrate
 * with the security filter chain, providing consistent error handling for
 * authentication failures across the application.
 * 
 * Maps to COBOL COSGN00C.cbl authentication error patterns:
 * - RESP code 13 (NOTFND): "User not found. Try again..." → USER_NOT_FOUND
 * - Password mismatch: "Wrong Password. Try again..." → INVALID_PASSWORD
 * - RESP code other than 0/13: "Unable to verify the User..." → Generic failure
 * 
 * Additional failure reasons support modern authentication requirements:
 * - ACCOUNT_LOCKED: User account is locked
 * - ACCOUNT_INACTIVE: User account is inactive
 * - TOKEN_EXPIRED: JWT token has expired
 * - TOKEN_INVALID: JWT token signature is invalid
 * 
 * Thread-safe and immutable for use in concurrent authentication operations.
 * Integrates with Spring Security's exception handling mechanism for consistent
 * HTTP 401 UNAUTHORIZED responses.
 */
public class AuthenticationFailedException extends AuthenticationException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * The username associated with the authentication failure.
     * May be null if the failure occurred before username extraction.
     */
    private final String username;
    
    /**
     * The specific reason for the authentication failure.
     * May be null if a generic failure message is sufficient.
     */
    private final AuthFailureReason reason;
    
    /**
     * Enumeration of authentication failure reasons.
     * 
     * Categorizes authentication failures for audit logging, error context,
     * and specific error response generation.
     */
    public enum AuthFailureReason {
        /**
         * User ID not found in UserSecurity repository.
         * Maps to COBOL RESP code 13 (NOTFND).
         */
        USER_NOT_FOUND,
        
        /**
         * Password verification failed (BCrypt mismatch).
         * Maps to COBOL password comparison failure.
         */
        INVALID_PASSWORD,
        
        /**
         * User account is locked and cannot authenticate.
         * Prevents authentication for locked accounts.
         */
        ACCOUNT_LOCKED,
        
        /**
         * User account is inactive and cannot authenticate.
         * Prevents authentication for inactive accounts.
         */
        ACCOUNT_INACTIVE,
        
        /**
         * JWT token has expired beyond its validity period.
         * Token must be refreshed or user must re-authenticate.
         */
        TOKEN_EXPIRED,
        
        /**
         * JWT token signature is invalid or token is malformed.
         * Token may be tampered with or improperly formatted.
         */
        TOKEN_INVALID
    }
    
    /**
     * Constructs an authentication failure exception with a message only.
     * 
     * Use this constructor for generic authentication failures where
     * specific username or reason information is not available or relevant.
     * 
     * @param message the detailed failure message describing the authentication error
     */
    public AuthenticationFailedException(String message) {
        super(message);
        this.username = null;
        this.reason = null;
    }
    
    /**
     * Constructs an authentication failure exception with message and username.
     * 
     * Use this constructor when the username is known but the specific
     * failure reason is generic or not categorized.
     * 
     * @param message the detailed failure message describing the authentication error
     * @param username the username that failed authentication (may be null)
     */
    public AuthenticationFailedException(String message, String username) {
        super(message);
        this.username = username;
        this.reason = null;
    }
    
    /**
     * Constructs an authentication failure exception with full context.
     * 
     * Use this constructor when both username and specific failure reason
     * are known for detailed audit logging and error reporting.
     * 
     * @param message the detailed failure message describing the authentication error
     * @param username the username that failed authentication (may be null)
     * @param reason the specific categorized reason for the failure (may be null)
     */
    public AuthenticationFailedException(String message, String username, AuthFailureReason reason) {
        super(message);
        this.username = username;
        this.reason = reason;
    }
    
    /**
     * Constructs an authentication failure exception with message and cause.
     * 
     * Use this constructor when authentication fails due to an underlying
     * exception (e.g., database connection error, encryption error).
     * 
     * @param message the detailed failure message describing the authentication error
     * @param cause the underlying exception that caused the authentication failure
     */
    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
        this.username = null;
        this.reason = null;
    }
    
    /**
     * Constructs an authentication failure exception with complete context and cause.
     * 
     * Use this constructor for comprehensive error reporting when all
     * context information and an underlying cause are available.
     * 
     * @param message the detailed failure message describing the authentication error
     * @param username the username that failed authentication (may be null)
     * @param reason the specific categorized reason for the failure (may be null)
     * @param cause the underlying exception that caused the authentication failure
     */
    public AuthenticationFailedException(String message, String username, AuthFailureReason reason, Throwable cause) {
        super(message, cause);
        this.username = username;
        this.reason = reason;
    }
    
    /**
     * Returns the username associated with the authentication failure.
     * 
     * The username is used for audit logging and security monitoring.
     * Note: The username is NOT included in HTTP error responses for
     * security reasons (prevents username enumeration attacks).
     * 
     * @return the username that failed authentication, or null if not available
     */
    public String getUsername() {
        return username;
    }
    
    /**
     * Returns the specific reason for the authentication failure.
     * 
     * The reason categorizes the failure for internal processing,
     * audit logging, and generating appropriate user-facing error messages.
     * 
     * @return the authentication failure reason, or null if not categorized
     */
    public AuthFailureReason getReason() {
        return reason;
    }
    
    /**
     * Returns a string representation of this exception for debugging.
     * 
     * Includes the message, username (if present), and reason (if present).
     * Used for logging and debugging purposes only.
     * 
     * @return a string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("AuthenticationFailedException: ");
        sb.append(getMessage());
        
        if (username != null) {
            sb.append(" [username=").append(username).append("]");
        }
        
        if (reason != null) {
            sb.append(" [reason=").append(reason).append("]");
        }
        
        return sb.toString();
    }
}
