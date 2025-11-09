/*
 * Program: ResourceNotFoundException.java
 * Layer: Exception handling
 * Function: Custom unchecked exception for entity not found scenarios
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific
 * language governing permissions and limitations under the License.
 */

package com.carddemo.exception;

/**
 * Custom unchecked exception for entity not found scenarios in the CardDemo application.
 * 
 * <p>This exception replaces COBOL CICS RESP-CD 13 (NOTFND) error handling patterns
 * from mainframe programs performing VSAM file READ operations. It is thrown when
 * database record lookups fail to find the requested entity.</p>
 * 
 * <p>COBOL patterns replaced:</p>
 * <ul>
 *   <li>COSGN00C.cbl: User lookup failures (lines 247-251) - "User not found"</li>
 *   <li>COACTVWC.cbl: Account retrieval failures - EXEC CICS READ RESP(13)</li>
 *   <li>COCRDSLC.cbl: Card lookup failures - VSAM key not found</li>
 *   <li>COTRN01C.cbl: Transaction detail retrieval failures</li>
 * </ul>
 * 
 * <p>Usage patterns:</p>
 * <pre>
 * // Simple message
 * throw new ResourceNotFoundException("User not found");
 * 
 * // Structured message with resource type and ID
 * throw new ResourceNotFoundException("Customer", customerId);
 * 
 * // With cause for cascading lookup failures
 * throw new ResourceNotFoundException("Account not found", underlyingException);
 * 
 * // JPA Optional integration
 * return accountRepository.findById(accountId)
 *     .orElseThrow(() -&gt; new ResourceNotFoundException("Account", accountId));
 * </pre>
 * 
 * <p>This exception is caught by GlobalExceptionHandler to return HTTP 404 Not Found
 * responses with descriptive error messages including resource type, requested identifier,
 * and timestamp.</p>
 * 
 * @see RuntimeException
 * @since 1.0
 */
public class ResourceNotFoundException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * Constructs a new ResourceNotFoundException with the specified detail message.
     * 
     * <p>This constructor is used for simple not-found scenarios where a descriptive
     * message is sufficient to identify the problem.</p>
     * 
     * <p>Example usage:</p>
     * <pre>
     * throw new ResourceNotFoundException("User not found: " + userId);
     * </pre>
     * 
     * <p>COBOL equivalent: WHEN 13 MOVE 'User not found. Try again ...' TO WS-MESSAGE</p>
     * 
     * @param message the detail message explaining which resource was not found
     */
    public ResourceNotFoundException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new ResourceNotFoundException with a structured error message.
     * 
     * <p>This constructor generates a standardized error message format:
     * "{resourceType} not found with ID: {resourceId}"</p>
     * 
     * <p>This format provides consistent error messaging across all entity types
     * and enables easy parsing by error handling middleware.</p>
     * 
     * <p>Example usage:</p>
     * <pre>
     * // For Customer entity
     * throw new ResourceNotFoundException("Customer", 123456789);
     * // Produces: "Customer not found with ID: 123456789"
     * 
     * // For Account entity
     * throw new ResourceNotFoundException("Account", 12345678901L);
     * // Produces: "Account not found with ID: 12345678901"
     * 
     * // For Card entity
     * throw new ResourceNotFoundException("Card", "4111111111111111");
     * // Produces: "Card not found with ID: 4111111111111111"
     * 
     * // For Transaction entity
     * throw new ResourceNotFoundException("Transaction", "TXN20240101-0001");
     * // Produces: "Transaction not found with ID: TXN20240101-0001"
     * </pre>
     * 
     * <p>COBOL equivalent: RESP-CD 13 handling with specific entity type identification</p>
     * 
     * @param resourceType the type of resource that was not found (e.g., "Customer", "Account", "Card")
     * @param resourceId the identifier of the resource that was not found (can be any type)
     */
    public ResourceNotFoundException(String resourceType, Object resourceId) {
        super(formatMessage(resourceType, resourceId));
    }
    
    /**
     * Constructs a new ResourceNotFoundException with the specified detail message and cause.
     * 
     * <p>This constructor is used when a resource lookup failure is caused by an underlying
     * exception, such as database connectivity issues or cascading relationship failures.</p>
     * 
     * <p>The cause is preserved for debugging and logging purposes, enabling full stack
     * trace analysis while maintaining the specific "not found" semantics at the API level.</p>
     * 
     * <p>Example usage:</p>
     * <pre>
     * try {
     *     Customer customer = customerRepository.findById(customerId)
     *         .orElseThrow(() -&gt; new ResourceNotFoundException("Customer not found"));
     *     Account account = accountRepository.findByCustomerId(customer.getCustomerId())
     *         .orElseThrow(() -&gt; new ResourceNotFoundException("Account not found"));
     * } catch (DataAccessException ex) {
     *     throw new ResourceNotFoundException("Unable to retrieve account data", ex);
     * }
     * </pre>
     * 
     * <p>COBOL equivalent: Multiple file access failures with cascading error messages</p>
     * 
     * @param message the detail message explaining which resource was not found
     * @param cause the underlying cause of the lookup failure (can be null)
     */
    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Formats a standardized error message for resource not found scenarios.
     * 
     * <p>This helper method ensures consistent error message formatting across
     * all resource types, making error messages predictable and parseable.</p>
     * 
     * <p>Message format: "{resourceType} not found with ID: {resourceId}"</p>
     * 
     * <p>The resourceId is converted to its string representation, handling null
     * values gracefully to prevent NullPointerExceptions during error reporting.</p>
     * 
     * @param resourceType the type of resource (e.g., "Customer", "Account", "Card", "Transaction", "User")
     * @param resourceId the identifier of the resource (can be Long, String, Integer, or any other type)
     * @return formatted error message string
     */
    private static String formatMessage(String resourceType, Object resourceId) {
        return String.format("%s not found with ID: %s", 
            resourceType, 
            resourceId != null ? resourceId.toString() : "null");
    }
    
    /**
     * Returns the detail message string of this exception.
     * 
     * <p>This method is inherited from RuntimeException and provides access to the
     * error message set during exception construction. It is used by logging frameworks
     * and the GlobalExceptionHandler to include the error message in HTTP responses.</p>
     * 
     * <p>The message format varies depending on which constructor was used:</p>
     * <ul>
     *   <li>ResourceNotFoundException(String): Returns the exact message provided</li>
     *   <li>ResourceNotFoundException(String, Object): Returns formatted message "{type} not found with ID: {id}"</li>
     *   <li>ResourceNotFoundException(String, Throwable): Returns the message with cause preserved</li>
     * </ul>
     * 
     * @return the detail message string (may be null if no message was provided)
     */
    @Override
    public String getMessage() {
        return super.getMessage();
    }
}
