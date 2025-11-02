/*
 * UserAlreadyExistsException.java
 *
 * Custom runtime exception thrown when attempting to create a user with a user ID 
 * that already exists in the database.
 *
 * This exception represents scenarios where EXEC CICS WRITE USRSEC operations return 
 * DFHRESP(DUPREC) response code (14) or COBOL file-status 22 (duplicate key) in 
 * mainframe programs during user creation operations.
 *
 * Transformation Notes:
 * - Maps from: COBOL USRSEC file DUPREC condition (DFHRESP(DUPREC)=14)
 * - Enables: HTTP 409 CONFLICT responses in REST API layer
 * - Usage: Thrown by UserManagementService.createUser() when userId already exists
 * - Integration: Handled by GlobalExceptionHandler for consistent error responses
 *
 * Copyright Amazon.com, Inc. or its affiliates.
 * All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.carddemo.exception;

/**
 * Exception thrown when attempting to create a user that already exists in the system.
 * 
 * <p>This exception is thrown when a user creation operation fails due to a duplicate
 * user ID constraint violation. It represents the Java equivalent of the COBOL/CICS
 * DFHRESP(DUPREC) response code that would be returned when attempting to write a
 * duplicate record to the USRSEC VSAM file.</p>
 * 
 * <p>In the target Spring Boot architecture, this exception enables the 
 * GlobalExceptionHandler to return an HTTP 409 CONFLICT status with appropriate
 * error details to REST API clients.</p>
 * 
 * <p><strong>COBOL Equivalent:</strong></p>
 * <pre>
 * EXEC CICS WRITE
 *      DATASET   ('USRSEC')
 *      FROM      (SEC-USER-DATA)
 *      RIDFLD    (SEC-USR-ID)
 *      RESP      (WS-RESP-CD)
 * END-EXEC
 * 
 * IF WS-RESP-CD = DFHRESP(DUPREC)
 *     MOVE 'User already exists' TO WS-MESSAGE
 * END-IF
 * </pre>
 * 
 * <p><strong>Usage Example:</strong></p>
 * <pre>
 * if (userSecurityRepository.existsById(userId)) {
 *     throw new UserAlreadyExistsException(
 *         "User with ID already exists", userId);
 * }
 * </pre>
 * 
 * <p>This exception is thread-safe and immutable, making it suitable for use in
 * concurrent environments. The userId field is stored as a final field to ensure
 * immutability and provide contextual information for error handling and logging.</p>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 1.0
 */
public class UserAlreadyExistsException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * The user ID that already exists in the system.
     * 
     * <p>This field stores the duplicate user identifier that caused the exception.
     * It corresponds to the SEC-USR-ID field from the COBOL USRSEC record structure
     * (PIC X(8) in the mainframe application).</p>
     * 
     * <p>The field is declared final to ensure immutability and thread safety.</p>
     */
    private final String userId;
    
    /**
     * Constructs a new UserAlreadyExistsException with the specified user ID.
     * 
     * <p>This constructor creates an exception with a default message indicating
     * that the user already exists. The userId parameter is stored for later
     * retrieval and error reporting.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <pre>
     * throw new UserAlreadyExistsException("USER0001");
     * </pre>
     * 
     * @param userId the user ID that already exists in the system (must not be null)
     */
    public UserAlreadyExistsException(String userId) {
        super("User with ID '" + userId + "' already exists in the system");
        this.userId = userId;
    }
    
    /**
     * Constructs a new UserAlreadyExistsException with a custom message and user ID.
     * 
     * <p>This constructor allows specification of both a custom error message and
     * the duplicate user ID. The message provides context-specific details while
     * the userId enables structured error handling.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <pre>
     * throw new UserAlreadyExistsException(
     *     "Cannot create user: duplicate user ID detected", "USER0001");
     * </pre>
     * 
     * @param message the detailed error message explaining the exception
     * @param userId the user ID that already exists in the system
     */
    public UserAlreadyExistsException(String message, String userId) {
        super(message);
        this.userId = userId;
    }
    
    /**
     * Constructs a new UserAlreadyExistsException with a message and root cause.
     * 
     * <p>This constructor is used when the duplicate user detection is triggered
     * by an underlying exception, such as a database constraint violation. The
     * cause parameter preserves the complete exception chain for debugging and
     * logging purposes.</p>
     * 
     * <p>Note: This constructor does not set a userId field value, as the exception
     * may be thrown in contexts where the specific user ID is not immediately
     * available. The userId field will be null when using this constructor.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <pre>
     * try {
     *     userRepository.save(newUser);
     * } catch (DataIntegrityViolationException e) {
     *     throw new UserAlreadyExistsException(
     *         "Database constraint violation: user already exists", e);
     * }
     * </pre>
     * 
     * @param message the detailed error message explaining the exception
     * @param cause the underlying cause of this exception (typically a database exception)
     */
    public UserAlreadyExistsException(String message, Throwable cause) {
        super(message, cause);
        this.userId = null;
    }
    
    /**
     * Constructs a new UserAlreadyExistsException with message, user ID, and cause.
     * 
     * <p>This constructor provides complete exception context by capturing the
     * custom message, the duplicate user ID, and the root cause exception. This
     * is the most comprehensive constructor for scenarios where all contextual
     * information is available.</p>
     * 
     * <p><strong>Usage:</strong></p>
     * <pre>
     * try {
     *     userRepository.save(newUser);
     * } catch (DataIntegrityViolationException e) {
     *     throw new UserAlreadyExistsException(
     *         "Failed to create user due to duplicate key",
     *         newUser.getUserId(),
     *         e);
     * }
     * </pre>
     * 
     * @param message the detailed error message explaining the exception
     * @param userId the user ID that already exists in the system
     * @param cause the underlying cause of this exception
     */
    public UserAlreadyExistsException(String message, String userId, Throwable cause) {
        super(message, cause);
        this.userId = userId;
    }
    
    /**
     * Returns the user ID that already exists in the system.
     * 
     * <p>This method provides access to the duplicate user identifier that caused
     * the exception. The returned value can be used for:</p>
     * <ul>
     *   <li>Structured error responses in REST APIs</li>
     *   <li>Detailed logging and audit trails</li>
     *   <li>Client-side error handling and user feedback</li>
     *   <li>Exception handling and recovery logic</li>
     * </ul>
     * 
     * <p><strong>Note:</strong> This method may return null if the exception was
     * constructed using the constructor that takes only a message and cause.</p>
     * 
     * <p><strong>Example Usage in GlobalExceptionHandler:</strong></p>
     * <pre>
     * &#64;ExceptionHandler(UserAlreadyExistsException.class)
     * public ResponseEntity&lt;ErrorResponse&gt; handleUserAlreadyExists(
     *         UserAlreadyExistsException ex) {
     *     ErrorResponse error = new ErrorResponse(
     *         HttpStatus.CONFLICT.value(),
     *         ex.getMessage(),
     *         "userId", ex.getUserId()
     *     );
     *     return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
     * }
     * </pre>
     * 
     * @return the user ID that already exists, or null if not available
     */
    public String getUserId() {
        return userId;
    }
    
    /**
     * Returns the detail message string of this exception.
     * 
     * <p>This method overrides the getMessage() method from Throwable to provide
     * consistent error message formatting. The message describes why the user
     * creation operation failed.</p>
     * 
     * <p>This method is included in the public API to satisfy the exports
     * specification and provide explicit documentation of the inherited behavior.</p>
     * 
     * @return the detail message string of this exception instance
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
