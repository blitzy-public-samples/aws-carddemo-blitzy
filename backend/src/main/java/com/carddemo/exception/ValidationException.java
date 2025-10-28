package com.carddemo.exception;

import lombok.Getter;

/**
 * Custom runtime exception for field validation failures in the CardDemo application.
 * <p>
 * This exception replaces COBOL field validation error flags and BMS map attribute checks
 * from the legacy mainframe system. It provides a standardized way to handle validation
 * errors across the Spring Boot application.
 * </p>
 * 
 * <h2>COBOL to Java Conversion</h2>
 * <p>
 * Converted from COBOL validation flag patterns found in:
 * <ul>
 *   <li>COACTUPC.cbl - Account update validation flags</li>
 *   <li>COTRN02C.cbl - Transaction entry validation flags</li>
 *   <li>COCRDUPC.cbl - Card update validation flags</li>
 * </ul>
 * </p>
 * 
 * <h3>COBOL Field Validation Flag Mappings:</h3>
 * <table border="1">
 *   <tr>
 *     <th>COBOL Flag</th>
 *     <th>Error Code</th>
 *     <th>Description</th>
 *   </tr>
 *   <tr>
 *     <td>FLG-ALPHA-NOT-OK</td>
 *     <td>VAL001</td>
 *     <td>Field must contain only alphabetic characters</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-MANDATORY-NOT-OK</td>
 *     <td>VAL002</td>
 *     <td>Required field is missing or empty</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-ALPHNANUM-NOT-OK</td>
 *     <td>VAL003</td>
 *     <td>Field contains invalid characters</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-YES-NO-NOT-OK</td>
 *     <td>VAL004</td>
 *     <td>Field must be 'Y' or 'N'</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-SIGNED-NUMBER-NOT-OK</td>
 *     <td>VAL005</td>
 *     <td>Field must be a valid numeric value</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-CARDFILTER-NOT-OK</td>
 *     <td>VAL006</td>
 *     <td>Invalid card number format</td>
 *   </tr>
 *   <tr>
 *     <td>FLG-ACCTFILTER-NOT-OK</td>
 *     <td>VAL007</td>
 *     <td>Invalid account ID format</td>
 *   </tr>
 * </table>
 * 
 * <h3>BMS Map Attribute Mappings:</h3>
 * <ul>
 *   <li><b>ASKIP</b> - Auto-skip protected field → Frontend enforces read-only</li>
 *   <li><b>PROT</b> - Protected field → Frontend enforces read-only</li>
 *   <li><b>NUM</b> - Numeric field → Triggers VAL005 if non-numeric</li>
 *   <li><b>BRT</b> - Bright field for errors → Highlighted in UI</li>
 * </ul>
 * 
 * <h2>Usage in Service Layer</h2>
 * <p>
 * Service methods throw this exception when input validation fails:
 * </p>
 * <pre>
 * // Example 1: Simple validation with message only
 * if (accountName == null || accountName.trim().isEmpty()) {
 *     throw new ValidationException("Account name cannot be empty");
 * }
 * 
 * // Example 2: Validation with field name
 * if (!isAlphabetic(firstName)) {
 *     throw new ValidationException(
 *         "First name must contain only alphabetic characters",
 *         "firstName"
 *     );
 * }
 * 
 * // Example 3: Full validation with error code, message, and field
 * if (!isValidCardNumber(cardNum)) {
 *     throw new ValidationException(
 *         "VAL006",
 *         "Invalid card number format - must be 16 digits",
 *         "cardNumber"
 *     );
 * }
 * </pre>
 * 
 * <h2>Exception Handling</h2>
 * <p>
 * This exception is caught by the {@code GlobalExceptionHandler} which returns
 * an HTTP 400 Bad Request response with a standardized {@code ErrorResponse} DTO
 * containing the error code, message, and field name.
 * </p>
 * 
 * <h3>REST API Response Example:</h3>
 * <pre>
 * HTTP/1.1 400 Bad Request
 * Content-Type: application/json
 * 
 * {
 *   "timestamp": "2025-10-25T10:30:15.123Z",
 *   "status": 400,
 *   "error": "Bad Request",
 *   "errorCode": "VAL002",
 *   "message": "Required field is missing",
 *   "fieldName": "accountId",
 *   "path": "/api/accounts"
 * }
 * </pre>
 * 
 * @author CardDemo Migration Team
 * @version 1.0
 * @since 2025-10-25
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @see com.carddemo.exception.BusinessException
 * @see com.carddemo.service.ValidationService
 */
@Getter
public class ValidationException extends RuntimeException {

    /**
     * Error code identifying the specific type of validation failure.
     * Corresponds to COBOL validation flag conditions.
     * <p>
     * Common error codes:
     * <ul>
     *   <li>VAL001 - Alphabetic validation failure</li>
     *   <li>VAL002 - Mandatory field missing</li>
     *   <li>VAL003 - Alphanumeric validation failure</li>
     *   <li>VAL004 - Yes/No field validation failure</li>
     *   <li>VAL005 - Numeric validation failure</li>
     *   <li>VAL006 - Card number validation failure</li>
     *   <li>VAL007 - Account ID validation failure</li>
     * </ul>
     * </p>
     */
    private final String errorCode;

    /**
     * The name of the field that failed validation.
     * Corresponds to WS-EDIT-VARIABLE-NAME in COBOL programs.
     * <p>
     * This field is used to:
     * <ul>
     *   <li>Identify which input field caused the validation error</li>
     *   <li>Enable frontend to highlight the specific field with error styling</li>
     *   <li>Provide precise error feedback to users</li>
     *   <li>Support form-level validation tracking</li>
     * </ul>
     * </p>
     * <p>
     * Example field names: "accountId", "cardNumber", "firstName", "transactionAmount"
     * </p>
     */
    private final String fieldName;

    /**
     * Constructs a ValidationException with only a message.
     * <p>
     * This constructor is used for general validation errors where the specific
     * field or error code is not relevant or cannot be determined.
     * </p>
     * <p>
     * Replaces COBOL pattern: {@code SET INPUT-ERROR TO TRUE}
     * </p>
     * 
     * @param message The detailed validation error message
     * 
     * @example
     * <pre>
     * throw new ValidationException("Invalid input data provided");
     * </pre>
     */
    public ValidationException(String message) {
        super(message);
        this.errorCode = null;
        this.fieldName = null;
    }

    /**
     * Constructs a ValidationException with a message and field name.
     * <p>
     * This constructor is used when the validation error is associated with a specific
     * field but a generic error code is sufficient.
     * </p>
     * <p>
     * Replaces COBOL pattern:
     * <pre>
     * MOVE 'FIELD-NAME' TO WS-EDIT-VARIABLE-NAME
     * SET FLG-ALPHA-NOT-OK TO TRUE
     * </pre>
     * </p>
     * 
     * @param message   The detailed validation error message
     * @param fieldName The name of the field that failed validation
     * 
     * @example
     * <pre>
     * throw new ValidationException(
     *     "Account name must contain only alphabetic characters",
     *     "accountName"
     * );
     * </pre>
     */
    public ValidationException(String message, String fieldName) {
        super(message);
        this.errorCode = null;
        this.fieldName = fieldName;
    }

    /**
     * Constructs a ValidationException with error code, message, and field name.
     * <p>
     * This is the most comprehensive constructor, providing full context about the
     * validation failure including the standardized error code, detailed message,
     * and the specific field that failed validation.
     * </p>
     * <p>
     * This constructor provides complete traceability from COBOL validation flags
     * to Java exception handling to REST API error responses.
     * </p>
     * <p>
     * Replaces COBOL pattern:
     * <pre>
     * MOVE 'FIELD-NAME' TO WS-EDIT-VARIABLE-NAME
     * SET FLG-MANDATORY-NOT-OK TO TRUE
     * MOVE 'VAL002' TO ERROR-CODE
     * MOVE 'Required field is missing' TO ERROR-MESSAGE
     * </pre>
     * </p>
     * 
     * @param errorCode A standardized error code (e.g., "VAL001", "VAL002")
     * @param message   The detailed validation error message
     * @param fieldName The name of the field that failed validation
     * 
     * @example
     * <pre>
     * // Alphabetic validation failure (maps to FLG-ALPHA-NOT-OK)
     * throw new ValidationException(
     *     "VAL001",
     *     "Field must contain only alphabetic characters",
     *     "firstName"
     * );
     * 
     * // Mandatory field missing (maps to FLG-MANDATORY-NOT-OK)
     * throw new ValidationException(
     *     "VAL002",
     *     "Required field is missing",
     *     "accountId"
     * );
     * 
     * // Alphanumeric validation failure (maps to FLG-ALPHNANUM-NOT-OK)
     * throw new ValidationException(
     *     "VAL003",
     *     "Field contains invalid characters",
     *     "streetAddress"
     * );
     * 
     * // Yes/No validation failure (maps to FLG-YES-NO-NOT-OK)
     * throw new ValidationException(
     *     "VAL004",
     *     "Field must be 'Y' or 'N'",
     *     "activeFlag"
     * );
     * 
     * // Numeric validation failure (maps to FLG-SIGNED-NUMBER-NOT-OK)
     * throw new ValidationException(
     *     "VAL005",
     *     "Field must be a valid numeric value",
     *     "transactionAmount"
     * );
     * 
     * // Card number validation failure (maps to FLG-CARDFILTER-NOT-OK)
     * throw new ValidationException(
     *     "VAL006",
     *     "Invalid card number format - must be 16 digits",
     *     "cardNumber"
     * );
     * 
     * // Account ID validation failure (maps to FLG-ACCTFILTER-NOT-OK)
     * throw new ValidationException(
     *     "VAL007",
     *     "Invalid account ID format - must be 11 digits",
     *     "accountId"
     * );
     * </pre>
     */
    public ValidationException(String errorCode, String message, String fieldName) {
        super(message);
        this.errorCode = errorCode;
        this.fieldName = fieldName;
    }

    /**
     * Returns a string representation of this validation exception.
     * <p>
     * Includes the error code, message, and field name (if available) to provide
     * complete diagnostic information for logging and debugging.
     * </p>
     * 
     * @return A formatted string containing all exception details
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ValidationException{");
        
        if (errorCode != null) {
            sb.append("errorCode='").append(errorCode).append('\'');
        }
        
        sb.append(", message='").append(getMessage()).append('\'');
        
        if (fieldName != null) {
            sb.append(", fieldName='").append(fieldName).append('\'');
        }
        
        sb.append('}');
        
        return sb.toString();
    }
}
