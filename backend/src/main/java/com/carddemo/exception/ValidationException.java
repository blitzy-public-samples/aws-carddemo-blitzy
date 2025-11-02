package com.carddemo.exception;

/**
 * Exception thrown when data validation fails in batch processing or service operations.
 * 
 * Used by Spring Batch processors (TransactionLoadProcessor, AccountDataProcessor, etc.) 
 * to indicate validation failures during item processing. When thrown by a processor, 
 * Spring Batch framework counts it toward the configured skip limit and optionally 
 * retries the operation based on job configuration.
 * 
 * Maps to COBOL validation logic patterns:
 * - INVALID KEY conditions from VSAM file operations
 * - COBOL 88-level condition checks (e.g., IF CARD-ACTIVE)
 * - Field validation rules from copybook definitions
 * 
 * Examples:
 * - Invalid card number (card not found in database)
 * - Invalid account ID (account not found or inactive)
 * - Missing required fields (null or empty values)
 * - Invalid data formats (incorrect length, invalid characters)
 * - Business rule violations (negative amounts, expired cards)
 * 
 * Per Section 0.9 requirements, maintains complete audit trail of validation failures
 * for regulatory compliance and error tracking.
 */
public class ValidationException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Error code for categorizing validation failures.
     * Can be used for error reporting and metrics tracking.
     */
    private String errorCode;
    
    /**
     * Field name that failed validation.
     * Useful for detailed error logging and debugging.
     */
    private String fieldName;
    
    /**
     * Invalid value that caused the validation failure.
     * Stored for audit trail and error analysis.
     */
    private Object invalidValue;
    
    /**
     * Constructs a new ValidationException with the specified detail message.
     * 
     * @param message the detail message explaining the validation failure
     */
    public ValidationException(String message) {
        super(message);
    }
    
    /**
     * Constructs a new ValidationException with the specified detail message and cause.
     * 
     * @param message the detail message explaining the validation failure
     * @param cause the underlying cause of the validation failure
     */
    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Constructs a new ValidationException with message, error code, and field details.
     * 
     * @param message the detail message explaining the validation failure
     * @param errorCode categorization code for the validation failure
     * @param fieldName name of the field that failed validation
     */
    public ValidationException(String message, String errorCode, String fieldName) {
        super(message);
        this.errorCode = errorCode;
        this.fieldName = fieldName;
    }
    
    /**
     * Constructs a new ValidationException with complete validation context.
     * 
     * @param message the detail message explaining the validation failure
     * @param errorCode categorization code for the validation failure
     * @param fieldName name of the field that failed validation
     * @param invalidValue the value that failed validation
     */
    public ValidationException(String message, String errorCode, String fieldName, Object invalidValue) {
        super(message);
        this.errorCode = errorCode;
        this.fieldName = fieldName;
        this.invalidValue = invalidValue;
    }
    
    /**
     * Gets the error code for this validation failure.
     * 
     * @return the error code, or null if not set
     */
    public String getErrorCode() {
        return errorCode;
    }
    
    /**
     * Sets the error code for this validation failure.
     * 
     * @param errorCode the error code to set
     */
    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }
    
    /**
     * Gets the name of the field that failed validation.
     * 
     * @return the field name, or null if not set
     */
    public String getFieldName() {
        return fieldName;
    }
    
    /**
     * Sets the name of the field that failed validation.
     * 
     * @param fieldName the field name to set
     */
    public void setFieldName(String fieldName) {
        this.fieldName = fieldName;
    }
    
    /**
     * Gets the invalid value that caused the validation failure.
     * 
     * @return the invalid value, or null if not set
     */
    public Object getInvalidValue() {
        return invalidValue;
    }
    
    /**
     * Sets the invalid value that caused the validation failure.
     * 
     * @param invalidValue the invalid value to set
     */
    public void setInvalidValue(Object invalidValue) {
        this.invalidValue = invalidValue;
    }
    
    /**
     * Returns a string representation including all validation context.
     * Overrides default toString() to provide detailed error information.
     * 
     * @return formatted string with validation details
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("ValidationException: ");
        sb.append(getMessage());
        
        if (errorCode != null) {
            sb.append(" [errorCode=").append(errorCode).append("]");
        }
        
        if (fieldName != null) {
            sb.append(" [fieldName=").append(fieldName).append("]");
        }
        
        if (invalidValue != null) {
            sb.append(" [invalidValue=").append(invalidValue).append("]");
        }
        
        return sb.toString();
    }
}
