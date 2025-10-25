/*
 * BusinessException.java
 *
 * Custom runtime exception for business logic violations and business rule failures
 * in the CardDemo application.
 *
 * Converted from COBOL APPL-RESULT error codes and business validation logic:
 * - COBOL source files: CBACT01C.cbl, CBTRN02C.cbl, COTRN02C.cbl
 * - Replaces COBOL error patterns:
 *   * MOVE 8 TO APPL-RESULT  → Transaction processing failure (BUS003)
 *   * MOVE 12 TO APPL-RESULT → General business rule violation (BUS001)
 *   * MOVE 16 TO APPL-RESULT → No more records available (BUS002)
 *
 * Original COBOL error handling:
 *   01  APPL-RESULT             PIC S9(9)   COMP.
 *       88  APPL-AOK            VALUE 0.
 *       88  APPL-EOF            VALUE 16.
 *
 * Java implementation uses Spring Boot exception handling patterns:
 * - Extends RuntimeException for unchecked exception handling
 * - Caught by GlobalExceptionHandler
 * - Returns HTTP 400 Bad Request or HTTP 409 Conflict
 * - Maps to standardized ErrorResponse DTO
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

import lombok.Getter;
import java.util.Map;

/**
 * BusinessException is thrown when business rules are violated during processing.
 * 
 * <p>This exception replaces COBOL APPL-RESULT error codes used throughout the
 * CardDemo mainframe application. Business logic errors that previously set
 * APPL-RESULT to non-zero values now throw this exception.</p>
 * 
 * <h3>Usage Examples:</h3>
 * <ul>
 *   <li><b>Insufficient Credit Limit:</b> When transaction amount exceeds available credit
 *       <pre>throw new BusinessException("BUS001", "Insufficient credit limit");</pre>
 *   </li>
 *   <li><b>Card Expired:</b> When attempting to process transaction with expired card
 *       <pre>throw new BusinessException("BUS001", "Card has expired");</pre>
 *   </li>
 *   <li><b>Account Inactive:</b> When attempting to use an inactive account
 *       <pre>throw new BusinessException("BUS001", "Account is inactive");</pre>
 *   </li>
 *   <li><b>Duplicate Transaction:</b> When transaction ID already exists
 *       <pre>throw new BusinessException("BUS003", "Duplicate transaction ID", 
 *            Map.of("transactionId", transId));</pre>
 *   </li>
 *   <li><b>Credit Limit Violation with Context:</b>
 *       <pre>throw new BusinessException("BUS001", "Credit limit exceeded",
 *            Map.of("accountId", acctId, "currentBalance", balance, 
 *                   "requestedAmount", amount, "creditLimit", limit));</pre>
 *   </li>
 * </ul>
 * 
 * <h3>COBOL Error Code Mapping:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Code</th>
 *     <th>COBOL Pattern</th>
 *     <th>Java Error Code</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>APPL-RESULT = 12</td>
 *     <td>MOVE 12 TO APPL-RESULT</td>
 *     <td>BUS001</td>
 *     <td>General business rule violation</td>
 *   </tr>
 *   <tr>
 *     <td>APPL-RESULT = 16</td>
 *     <td>MOVE 16 TO APPL-RESULT</td>
 *     <td>BUS002</td>
 *     <td>No more records available (end of file)</td>
 *   </tr>
 *   <tr>
 *     <td>APPL-RESULT = 8</td>
 *     <td>MOVE 8 TO APPL-RESULT</td>
 *     <td>BUS003</td>
 *     <td>Transaction processing failed</td>
 *   </tr>
 * </table>
 * 
 * <h3>Integration with GlobalExceptionHandler:</h3>
 * <p>This exception is caught by {@code @ControllerAdvice} annotated GlobalExceptionHandler
 * which converts it to appropriate HTTP responses:</p>
 * <ul>
 *   <li><b>BUS001:</b> HTTP 400 Bad Request - Business rule validation failed</li>
 *   <li><b>BUS002:</b> HTTP 404 Not Found - Resource exhausted or not found</li>
 *   <li><b>BUS003:</b> HTTP 409 Conflict - Transaction processing conflict</li>
 * </ul>
 * 
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see com.carddemo.model.dto.ErrorResponse
 */
@Getter
public class BusinessException extends RuntimeException {
    
    /**
     * Business error code (e.g., BUS001, BUS002, BUS003).
     * Maps to COBOL APPL-RESULT values for error categorization.
     * Used by GlobalExceptionHandler to determine HTTP status code.
     */
    private final String errorCode;
    
    /**
     * Optional context information providing additional details about the error.
     * 
     * <p>Replaces COBOL WORKING-STORAGE variables that held error context:</p>
     * <ul>
     *   <li>WS-ACCT-ID-N → "accountId" key in context map</li>
     *   <li>WS-TRAN-AMT-N → "transactionAmount" key in context map</li>
     *   <li>WS-CARD-NUM-N → "cardNumber" key in context map</li>
     * </ul>
     * 
     * <p>Example context data:</p>
     * <pre>
     * Map.of(
     *     "accountId", 12345678901L,
     *     "currentBalance", new BigDecimal("1500.00"),
     *     "requestedAmount", new BigDecimal("2000.00"),
     *     "creditLimit", new BigDecimal("5000.00"),
     *     "availableCredit", new BigDecimal("3500.00")
     * )
     * </pre>
     */
    private final Map<String, Object> context;
    
    /**
     * Constructs a new BusinessException with the specified detail message.
     * Error code defaults to "BUS001" (general business rule violation).
     * 
     * <p>Equivalent to COBOL pattern:</p>
     * <pre>
     * MOVE 12 TO APPL-RESULT
     * DISPLAY 'ERROR: [message]'
     * </pre>
     * 
     * @param message the detail message explaining the business rule violation
     */
    public BusinessException(String message) {
        super(message);
        this.errorCode = "BUS001";
        this.context = null;
    }
    
    /**
     * Constructs a new BusinessException with specified error code and message.
     * 
     * <p>Equivalent to COBOL pattern:</p>
     * <pre>
     * MOVE [code] TO APPL-RESULT
     * MOVE [message] TO WS-MESSAGE
     * </pre>
     * 
     * @param errorCode the business error code (BUS001, BUS002, BUS003, etc.)
     * @param message the detail message explaining the business rule violation
     */
    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.context = null;
    }
    
    /**
     * Constructs a new BusinessException with error code, message, and context data.
     * 
     * <p>Equivalent to COBOL pattern:</p>
     * <pre>
     * MOVE 12 TO APPL-RESULT
     * MOVE [message] TO WS-MESSAGE
     * MOVE [account-id] TO WS-ACCT-ID-N
     * MOVE [amount] TO WS-TRAN-AMT-N
     * </pre>
     * 
     * <p>The context map provides rich error information for debugging and logging.
     * Context data is included in ErrorResponse returned to API clients.</p>
     * 
     * @param errorCode the business error code (BUS001, BUS002, BUS003, etc.)
     * @param message the detail message explaining the business rule violation
     * @param context additional context data as key-value pairs (e.g., account ID, amounts)
     */
    public BusinessException(String errorCode, String message, Map<String, Object> context) {
        super(message);
        this.errorCode = errorCode;
        this.context = context;
    }
    
    /**
     * Returns the error code for this exception.
     * Generated by Lombok @Getter annotation.
     * 
     * @return the business error code (BUS001, BUS002, BUS003, etc.)
     */
    // public String getErrorCode() - generated by Lombok
    
    /**
     * Returns the detail message for this exception.
     * Inherited from RuntimeException and exposed by Lombok @Getter annotation.
     * 
     * @return the detail message explaining the business rule violation
     */
    // public String getMessage() - inherited from Throwable
    
    /**
     * Returns the context map for this exception.
     * Generated by Lombok @Getter annotation.
     * 
     * @return the context map with additional error details, or null if no context provided
     */
    // public Map<String, Object> getContext() - generated by Lombok
}
