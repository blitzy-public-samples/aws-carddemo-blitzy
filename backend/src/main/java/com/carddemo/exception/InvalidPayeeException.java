package com.carddemo.exception;

/**
 * Custom runtime exception thrown when payee validation fails during bill payment processing.
 * <p>
 * This exception is raised when a payee with the specified payee ID does not exist, is inactive,
 * or is not authorized for payments. It carries payee identifier context enabling the
 * GlobalExceptionHandler to return HTTP 400 BAD REQUEST responses with detailed error information.
 * </p>
 * <p>
 * As a RuntimeException, this exception supports automatic transaction rollback when thrown within
 * @Transactional methods, ensuring data integrity during bill payment operations.
 * </p>
 * <p>
 * This exception is part of the domain-specific exception hierarchy for the CardDemo application,
 * migrated from COBOL COBIL00C bill payment validation logic.
 * </p>
 *
 * @see com.carddemo.service.BillPaymentService
 * @see com.carddemo.exception.GlobalExceptionHandler
 * @since 1.0
 */
public class InvalidPayeeException extends RuntimeException {

    /**
     * The unique identifier of the payee that failed validation.
     * This field is immutable and thread-safe.
     */
    private final String payeeId;

    /**
     * The name of the payee that failed validation, if available.
     * May be null if payee name was not resolved before validation failure.
     */
    private final String payeeName;

    /**
     * The specific reason for payee validation failure.
     * Provides detailed context for error handling and logging.
     */
    private final PayeeValidationReason reason;

    /**
     * Enumeration of possible payee validation failure reasons.
     * <p>
     * These reasons map to specific business rule violations in the bill payment process,
     * preserving validation logic from the original COBOL COBIL00C program.
     * </p>
     */
    public enum PayeeValidationReason {
        /**
         * Payee with the specified ID does not exist in the system.
         * Corresponds to COBOL file-status 23 (record not found) in COBIL00C.
         */
        PAYEE_NOT_FOUND,

        /**
         * Payee exists but is marked as inactive and cannot receive payments.
         * Corresponds to COBOL 88-level condition PAYEE-INACTIVE-FLAG validation.
         */
        PAYEE_INACTIVE,

        /**
         * Payee exists but is not authorized for payments from the specified account.
         * Corresponds to COBOL PAYEE-ACCT-XREF validation logic.
         */
        PAYEE_NOT_AUTHORIZED,

        /**
         * Payee data is invalid, incomplete, or corrupted.
         * Corresponds to COBOL data validation failures in COBIL00C.
         */
        INVALID_PAYEE_DATA
    }

    /**
     * Constructs a new InvalidPayeeException with the specified payee ID.
     * <p>
     * This constructor generates a default error message and sets the reason to PAYEE_NOT_FOUND.
     * Used when payee lookup fails to find a matching record.
     * </p>
     *
     * @param payeeId the unique identifier of the payee that failed validation
     */
    public InvalidPayeeException(String payeeId) {
        super(String.format("Invalid payee: %s", payeeId));
        this.payeeId = payeeId;
        this.payeeName = null;
        this.reason = PayeeValidationReason.PAYEE_NOT_FOUND;
    }

    /**
     * Constructs a new InvalidPayeeException with the specified payee ID and validation reason.
     * <p>
     * This constructor generates a contextual error message based on the validation reason.
     * Used when the specific validation failure type is known but payee name is unavailable.
     * </p>
     *
     * @param payeeId the unique identifier of the payee that failed validation
     * @param reason the specific reason for validation failure
     */
    public InvalidPayeeException(String payeeId, PayeeValidationReason reason) {
        super(String.format("Invalid payee %s: %s", payeeId, formatReason(reason)));
        this.payeeId = payeeId;
        this.payeeName = null;
        this.reason = reason;
    }

    /**
     * Constructs a new InvalidPayeeException with a custom message and payee ID.
     * <p>
     * This constructor allows for detailed, context-specific error messages while preserving
     * the payee identifier for error handling. The reason defaults to PAYEE_NOT_FOUND.
     * </p>
     *
     * @param message the custom detail message explaining the validation failure
     * @param payeeId the unique identifier of the payee that failed validation
     */
    public InvalidPayeeException(String message, String payeeId) {
        super(message);
        this.payeeId = payeeId;
        this.payeeName = null;
        this.reason = PayeeValidationReason.PAYEE_NOT_FOUND;
    }

    /**
     * Constructs a new InvalidPayeeException with full context information.
     * <p>
     * This is the most comprehensive constructor, capturing all available information about
     * the validation failure including custom message, payee ID, payee name, and reason.
     * Used when complete payee context is available at the point of failure.
     * </p>
     *
     * @param message the custom detail message explaining the validation failure
     * @param payeeId the unique identifier of the payee that failed validation
     * @param payeeName the name of the payee that failed validation (may be null)
     * @param reason the specific reason for validation failure
     */
    public InvalidPayeeException(String message, String payeeId, String payeeName, PayeeValidationReason reason) {
        super(message);
        this.payeeId = payeeId;
        this.payeeName = payeeName;
        this.reason = reason;
    }

    /**
     * Constructs a new InvalidPayeeException with a custom message and root cause.
     * <p>
     * This constructor is used when payee validation fails due to an underlying system error
     * (e.g., database access exception, network timeout). The payee context fields are set to
     * null as they may not be available when infrastructure failures occur.
     * </p>
     *
     * @param message the custom detail message explaining the validation failure
     * @param cause the underlying exception that caused the validation failure
     */
    public InvalidPayeeException(String message, Throwable cause) {
        super(message, cause);
        this.payeeId = null;
        this.payeeName = null;
        this.reason = PayeeValidationReason.INVALID_PAYEE_DATA;
    }

    /**
     * Returns the unique identifier of the payee that failed validation.
     * <p>
     * This identifier can be used for logging, error reporting, and correlation with
     * other system records. May be null if the exception was caused by an infrastructure
     * failure before payee identification.
     * </p>
     *
     * @return the payee ID, or null if not available
     */
    public String getPayeeId() {
        return payeeId;
    }

    /**
     * Returns the name of the payee that failed validation.
     * <p>
     * The payee name provides user-friendly context in error messages and logs.
     * May be null if the payee name was not resolved before validation failure,
     * or if the exception was caused by an infrastructure failure.
     * </p>
     *
     * @return the payee name, or null if not available
     */
    public String getPayeeName() {
        return payeeName;
    }

    /**
     * Returns the specific reason for payee validation failure.
     * <p>
     * The validation reason enables fine-grained error handling and allows the
     * GlobalExceptionHandler to return appropriate HTTP response codes and error messages.
     * This supports the business rule validation patterns from the original COBOL COBIL00C program.
     * </p>
     *
     * @return the validation failure reason
     */
    public PayeeValidationReason getReason() {
        return reason;
    }

    /**
     * Formats a PayeeValidationReason enum value into a human-readable error description.
     * <p>
     * This utility method supports error message generation in constructors and provides
     * consistent messaging across the application.
     * </p>
     *
     * @param reason the validation reason to format
     * @return a human-readable description of the validation failure
     */
    private static String formatReason(PayeeValidationReason reason) {
        if (reason == null) {
            return "Unknown validation failure";
        }
        
        switch (reason) {
            case PAYEE_NOT_FOUND:
                return "Payee not found in system";
            case PAYEE_INACTIVE:
                return "Payee is inactive and cannot receive payments";
            case PAYEE_NOT_AUTHORIZED:
                return "Payee is not authorized for this account";
            case INVALID_PAYEE_DATA:
                return "Payee data is invalid or corrupted";
            default:
                return "Unknown validation failure";
        }
    }

    /**
     * Returns a string representation of this exception including all context information.
     * <p>
     * This method enhances debugging and logging by including payee ID, name, and reason
     * in addition to the standard exception message and stack trace.
     * </p>
     *
     * @return a detailed string representation of this exception
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getClass().getName());
        
        String message = getMessage();
        if (message != null) {
            sb.append(": ").append(message);
        }
        
        if (payeeId != null) {
            sb.append(" [Payee ID: ").append(payeeId);
            
            if (payeeName != null) {
                sb.append(", Name: ").append(payeeName);
            }
            
            if (reason != null) {
                sb.append(", Reason: ").append(reason);
            }
            
            sb.append("]");
        }
        
        return sb.toString();
    }
}
