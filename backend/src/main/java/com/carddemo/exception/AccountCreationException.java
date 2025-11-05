package com.carddemo.exception;

/**
 * Custom runtime exception thrown when account creation operations fail due to 
 * business rule validation errors or system constraints.
 * 
 * <p>This exception represents scenarios where account setup cannot be completed, such as:
 * <ul>
 *   <li>Customer not found in the system</li>
 *   <li>Invalid credit limit values (negative, exceeds maximum, below minimum)</li>
 *   <li>Duplicate account number generation conflicts</li>
 *   <li>Cross-reference (XREF) creation failures during account setup</li>
 *   <li>Initial transaction record creation failures</li>
 *   <li>Database constraint violations or integrity issues</li>
 *   <li>General validation errors during account creation workflow</li>
 * </ul>
 * 
 * <p>This exception carries detailed context about the failure including:
 * <ul>
 *   <li>Customer identifier for which account creation was attempted</li>
 *   <li>Specific failure reason enum indicating the root cause</li>
 *   <li>Descriptive error message suitable for logging and debugging</li>
 *   <li>Optional causal exception for exception chaining</li>
 * </ul>
 * 
 * <p><b>COBOL Mapping:</b> This exception maps to account creation error scenarios 
 * in COBOL programs such as COACTADD.cbl where account setup operations fail due to 
 * validation errors, customer verification failures, or database integrity issues.
 * It preserves the error semantics from mainframe implementation ensuring consistent 
 * error handling patterns in the modernized Java application.
 * 
 * <p><b>Usage Example:</b>
 * <pre>{@code
 * // In AccountCreationService.createAccount()
 * Customer customer = customerRepository.findById(customerId)
 *     .orElseThrow(() -> new AccountCreationException(
 *         "Customer not found for account creation",
 *         customerId,
 *         FailureReason.CUSTOMER_NOT_FOUND
 *     ));
 * }</pre>
 * 
 * <p><b>HTTP Response Mapping:</b> The GlobalExceptionHandler translates this exception into:
 * <ul>
 *   <li>HTTP 400 BAD_REQUEST for validation errors (INVALID_CREDIT_LIMIT, VALIDATION_ERROR)</li>
 *   <li>HTTP 409 CONFLICT for duplicate account number scenarios</li>
 *   <li>HTTP 404 NOT_FOUND for customer not found scenarios</li>
 * </ul>
 * 
 * <p>This exception extends {@link RuntimeException} to support automatic transaction 
 * rollback via Spring's {@code @Transactional} annotation without requiring explicit 
 * declaration in method signatures.
 * 
 * @see com.carddemo.service.AccountCreationService
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @since 1.0
 */
public class AccountCreationException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * The customer identifier for which account creation was attempted.
     * This provides context about which customer's account creation failed,
     * enabling detailed error logging and client error responses.
     */
    private final String customerId;
    
    /**
     * The specific reason why account creation failed.
     * This enum value categorizes the failure type enabling appropriate
     * error handling and HTTP status code mapping in GlobalExceptionHandler.
     */
    private final FailureReason failureReason;
    
    /**
     * Enumeration of possible account creation failure reasons.
     * Each value represents a specific category of failure that can occur
     * during the account creation workflow, enabling precise error handling
     * and appropriate client error responses.
     */
    public enum FailureReason {
        /**
         * Customer with the specified ID does not exist in the system.
         * The account creation service validates customer existence before
         * creating an account, and this failure reason indicates the lookup failed.
         */
        CUSTOMER_NOT_FOUND,
        
        /**
         * Credit limit value is invalid (negative, zero, exceeds maximum, below minimum).
         * Business rules define valid credit limit ranges, and this failure indicates
         * the requested credit limit violates these constraints.
         */
        INVALID_CREDIT_LIMIT,
        
        /**
         * Generated account number already exists in the system.
         * While rare with proper sequence generation, this indicates a duplicate
         * account number conflict requiring retry with a new account number.
         */
        DUPLICATE_ACCOUNT_NUMBER,
        
        /**
         * Cross-reference (XREF) record creation failed during account setup.
         * Account creation requires associated cross-reference entries, and this
         * failure indicates the XREF creation did not complete successfully.
         */
        XREF_CREATION_FAILED,
        
        /**
         * Initial transaction record creation failed during account setup.
         * New accounts may require an initial transaction record (e.g., opening balance),
         * and this failure indicates that transaction creation did not complete.
         */
        INITIAL_TRANSACTION_FAILED,
        
        /**
         * Database constraint violation or general database error occurred.
         * This includes foreign key violations, unique constraint violations,
         * connection failures, or other database-level errors.
         */
        DATABASE_ERROR,
        
        /**
         * General validation error not covered by specific failure reasons.
         * This catch-all category handles validation failures such as invalid
         * account status values, missing required fields, or other input validation issues.
         */
        VALIDATION_ERROR
    }
    
    /**
     * Constructs a new account creation exception with customer ID and failure reason.
     * The exception message is auto-generated from the failure reason.
     * 
     * @param customerId the customer identifier for which account creation failed
     * @param failureReason the specific reason for account creation failure
     */
    public AccountCreationException(String customerId, FailureReason failureReason) {
        super(String.format("Account creation failed for customer %s: %s", 
            customerId, 
            failureReason.name()));
        this.customerId = customerId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new account creation exception with a custom message,
     * customer ID, and failure reason.
     * 
     * @param message the detailed error message describing the failure
     * @param customerId the customer identifier for which account creation failed
     * @param failureReason the specific reason for account creation failure
     */
    public AccountCreationException(String message, String customerId, FailureReason failureReason) {
        super(message);
        this.customerId = customerId;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new account creation exception with a custom message and cause.
     * This constructor is used when wrapping lower-level exceptions without specific
     * customer or failure reason context available at the throw site.
     * 
     * @param message the detailed error message describing the failure
     * @param cause the underlying cause of the account creation failure
     */
    public AccountCreationException(String message, Throwable cause) {
        super(message, cause);
        this.customerId = null;
        this.failureReason = null;
    }
    
    /**
     * Constructs a new account creation exception with full context including
     * message, cause, customer ID, and failure reason.
     * This is the most detailed constructor providing complete error context
     * for comprehensive error handling and logging.
     * 
     * @param message the detailed error message describing the failure
     * @param cause the underlying cause of the account creation failure
     * @param customerId the customer identifier for which account creation failed
     * @param failureReason the specific reason for account creation failure
     */
    public AccountCreationException(String message, Throwable cause, String customerId, FailureReason failureReason) {
        super(message, cause);
        this.customerId = customerId;
        this.failureReason = failureReason;
    }
    
    /**
     * Returns the customer identifier for which account creation failed.
     * 
     * @return the customer ID, or null if not available
     */
    public String getCustomerId() {
        return customerId;
    }
    
    /**
     * Returns the specific reason why account creation failed.
     * 
     * @return the failure reason enum value, or null if not available
     */
    public FailureReason getFailureReason() {
        return failureReason;
    }
}
