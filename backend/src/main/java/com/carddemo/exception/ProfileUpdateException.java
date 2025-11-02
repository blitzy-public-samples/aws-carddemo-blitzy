/*
 * ProfileUpdateException.java
 *
 * Custom runtime exception for user profile update operation failures.
 * Maps COBOL COUSR01C user profile update error conditions to Spring exception hierarchy.
 *
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.carddemo.exception;

/**
 * Custom runtime exception thrown when user profile update operations fail due to
 * business rule validation errors, concurrent modification conflicts, authorization
 * failures, or system constraints.
 * 
 * <p>This exception represents scenarios where profile modification cannot be completed
 * such as:</p>
 * <ul>
 *   <li>User not found in the system</li>
 *   <li>Invalid password changes not meeting security requirements</li>
 *   <li>Unauthorized user type modifications (regular user attempting admin changes)</li>
 *   <li>Concurrent update conflicts (optimistic locking failures)</li>
 *   <li>User account locked or suspended</li>
 *   <li>Password requirements not satisfied</li>
 *   <li>General validation errors</li>
 *   <li>Database constraint violations</li>
 * </ul>
 * 
 * <p>The exception carries detailed context about the failure cause including user
 * identifier and specific validation failure reason, enabling precise error handling
 * and appropriate HTTP responses (400 BAD REQUEST, 403 FORBIDDEN, or 409 CONFLICT)
 * to REST API clients.</p>
 * 
 * <p>This exception is immutable and thread-safe, designed for use in concurrent
 * Spring Boot environments with @Transactional rollback support.</p>
 * 
 * <p><b>Mapping to HTTP Status Codes:</b></p>
 * <ul>
 *   <li>HTTP 400 BAD_REQUEST: VALIDATION_ERROR, PASSWORD_REQUIREMENTS_NOT_MET,
 *       INVALID_PASSWORD_CHANGE</li>
 *   <li>HTTP 403 FORBIDDEN: UNAUTHORIZED_TYPE_CHANGE, USER_LOCKED</li>
 *   <li>HTTP 404 NOT_FOUND: USER_NOT_FOUND</li>
 *   <li>HTTP 409 CONFLICT: CONCURRENT_UPDATE_CONFLICT</li>
 *   <li>HTTP 500 INTERNAL_SERVER_ERROR: DATABASE_ERROR</li>
 * </ul>
 * 
 * <p><b>Usage in UserProfileService:</b></p>
 * <pre>
 * if (!userExists) {
 *     throw new ProfileUpdateException(userId, UpdateFailureReason.USER_NOT_FOUND);
 * }
 * 
 * if (isUnauthorizedTypeChange) {
 *     throw new ProfileUpdateException(
 *         "Regular users cannot change user type",
 *         userId,
 *         UpdateFailureReason.UNAUTHORIZED_TYPE_CHANGE
 *     );
 * }
 * </pre>
 * 
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @since 1.0
 * @version 1.0
 */
public class ProfileUpdateException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * User identifier associated with the failed profile update operation.
     * This field is immutable and thread-safe.
     */
    private final String userId;
    
    /**
     * Specific reason for the profile update failure.
     * This field is immutable and thread-safe.
     */
    private final UpdateFailureReason failureReason;
    
    /**
     * Enumeration of specific failure reasons for user profile update operations.
     * 
     * <p>Each reason maps to specific error handling logic and HTTP status codes
     * in the GlobalExceptionHandler.</p>
     */
    public enum UpdateFailureReason {
        /**
         * User with the specified identifier does not exist in the system.
         * Maps to HTTP 404 NOT_FOUND.
         */
        USER_NOT_FOUND,
        
        /**
         * Password change request is invalid (e.g., old password incorrect,
         * new password same as old password).
         * Maps to HTTP 400 BAD_REQUEST.
         */
        INVALID_PASSWORD_CHANGE,
        
        /**
         * User attempted to change user type without proper authorization
         * (regular user trying to become admin).
         * Maps to HTTP 403 FORBIDDEN.
         */
        UNAUTHORIZED_TYPE_CHANGE,
        
        /**
         * Another concurrent update to the same user profile was detected
         * (optimistic locking version mismatch).
         * Maps to HTTP 409 CONFLICT.
         */
        CONCURRENT_UPDATE_CONFLICT,
        
        /**
         * User account is locked or suspended and cannot be updated.
         * Maps to HTTP 403 FORBIDDEN.
         */
        USER_LOCKED,
        
        /**
         * New password does not meet security requirements (length, complexity,
         * character types).
         * Maps to HTTP 400 BAD_REQUEST.
         */
        PASSWORD_REQUIREMENTS_NOT_MET,
        
        /**
         * General validation error for user profile fields (invalid format,
         * missing required fields, invalid data).
         * Maps to HTTP 400 BAD_REQUEST.
         */
        VALIDATION_ERROR,
        
        /**
         * Database constraint violation or persistence error occurred during
         * the update operation.
         * Maps to HTTP 500 INTERNAL_SERVER_ERROR.
         */
        DATABASE_ERROR
    }
    
    /**
     * Constructs a new ProfileUpdateException with user ID and failure reason.
     * 
     * <p>This constructor creates an exception with a default message generated
     * from the failure reason.</p>
     * 
     * @param userId the identifier of the user whose profile update failed;
     *               must not be null or empty
     * @param failureReason the specific reason for the update failure;
     *                     must not be null
     * @throws IllegalArgumentException if userId is null or empty, or if
     *                                 failureReason is null
     */
    public ProfileUpdateException(String userId, UpdateFailureReason failureReason) {
        super(generateDefaultMessage(userId, failureReason));
        validateUserId(userId);
        validateFailureReason(failureReason);
        this.userId = userId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new ProfileUpdateException with custom message, user ID,
     * and failure reason.
     * 
     * <p>This constructor allows specifying a detailed custom error message
     * while preserving the user context and failure reason.</p>
     * 
     * @param message the detailed error message describing the failure;
     *               must not be null or empty
     * @param userId the identifier of the user whose profile update failed;
     *              must not be null or empty
     * @param failureReason the specific reason for the update failure;
     *                     must not be null
     * @throws IllegalArgumentException if any parameter is null or empty
     */
    public ProfileUpdateException(String message, String userId, 
                                  UpdateFailureReason failureReason) {
        super(message);
        validateMessage(message);
        validateUserId(userId);
        validateFailureReason(failureReason);
        this.userId = userId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new ProfileUpdateException with custom message and root cause.
     * 
     * <p>This constructor is used when wrapping a lower-level exception
     * (e.g., database exception) without specific user context.</p>
     * 
     * @param message the detailed error message describing the failure;
     *               must not be null or empty
     * @param cause the underlying cause of the profile update failure;
     *             may be null
     * @throws IllegalArgumentException if message is null or empty
     */
    public ProfileUpdateException(String message, Throwable cause) {
        super(message, cause);
        validateMessage(message);
        this.userId = null;
        this.failureReason = UpdateFailureReason.DATABASE_ERROR;
    }
    
    /**
     * Constructs a new ProfileUpdateException with custom message, root cause,
     * user ID, and failure reason.
     * 
     * <p>This is the most comprehensive constructor, capturing all available
     * context about the failure including the underlying system exception.</p>
     * 
     * @param message the detailed error message describing the failure;
     *               must not be null or empty
     * @param cause the underlying cause of the profile update failure;
     *             may be null
     * @param userId the identifier of the user whose profile update failed;
     *              must not be null or empty
     * @param failureReason the specific reason for the update failure;
     *                     must not be null
     * @throws IllegalArgumentException if message, userId, or failureReason
     *                                 is null or empty
     */
    public ProfileUpdateException(String message, Throwable cause, 
                                  String userId, UpdateFailureReason failureReason) {
        super(message, cause);
        validateMessage(message);
        validateUserId(userId);
        validateFailureReason(failureReason);
        this.userId = userId;
        this.failureReason = failureReason;
    }
    
    /**
     * Returns the identifier of the user whose profile update failed.
     * 
     * @return the user ID, or null if not available in this exception context
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Returns the specific reason for the profile update failure.
     * 
     * @return the failure reason; never null
     */
    public UpdateFailureReason getFailureReason() {
        return failureReason;
    }
    
    /**
     * Generates a default error message based on user ID and failure reason.
     * 
     * @param userId the user identifier
     * @param reason the failure reason
     * @return a formatted error message
     */
    private static String generateDefaultMessage(String userId, UpdateFailureReason reason) {
        if (reason == null) {
            return "Profile update failed for user: " + userId;
        }
        
        switch (reason) {
            case USER_NOT_FOUND:
                return "User not found: " + userId;
            case INVALID_PASSWORD_CHANGE:
                return "Invalid password change request for user: " + userId;
            case UNAUTHORIZED_TYPE_CHANGE:
                return "Unauthorized user type change attempt for user: " + userId;
            case CONCURRENT_UPDATE_CONFLICT:
                return "Concurrent update conflict detected for user: " + userId;
            case USER_LOCKED:
                return "User account is locked: " + userId;
            case PASSWORD_REQUIREMENTS_NOT_MET:
                return "Password requirements not met for user: " + userId;
            case VALIDATION_ERROR:
                return "Profile validation error for user: " + userId;
            case DATABASE_ERROR:
                return "Database error during profile update for user: " + userId;
            default:
                return "Profile update failed for user: " + userId;
        }
    }
    
    /**
     * Validates that the user ID is not null or empty.
     * 
     * @param userId the user ID to validate
     * @throws IllegalArgumentException if userId is null or empty
     */
    private static void validateUserId(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("User ID must not be null or empty");
        }
    }
    
    /**
     * Validates that the failure reason is not null.
     * 
     * @param reason the failure reason to validate
     * @throws IllegalArgumentException if reason is null
     */
    private static void validateFailureReason(UpdateFailureReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("Failure reason must not be null");
        }
    }
    
    /**
     * Validates that the message is not null or empty.
     * 
     * @param message the message to validate
     * @throws IllegalArgumentException if message is null or empty
     */
    private static void validateMessage(String message) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Message must not be null or empty");
        }
    }
}
