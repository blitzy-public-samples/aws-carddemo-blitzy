/*
 * CardUpdateException.java
 * 
 * Custom runtime exception for card update operation failures in the CardDemo application.
 * 
 * This exception is thrown when card update operations fail due to business rule validation 
 * errors, concurrent modification conflicts, or system constraints. It maps directly to error 
 * conditions from the legacy COBOL COCRDUPC.cbl program and provides detailed context about 
 * the failure cause.
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
 * CardUpdateException represents failures during card update operations.
 * 
 * This exception is thrown in scenarios where card modification cannot be completed, including:
 * <ul>
 *   <li>Invalid status transitions (e.g., reactivating a stolen card)</li>
 *   <li>Expired card updates</li>
 *   <li>Concurrent update conflicts (optimistic locking failures)</li>
 *   <li>Invalid expiration dates</li>
 *   <li>Database constraint violations</li>
 *   <li>Record locking failures</li>
 * </ul>
 * 
 * The exception carries detailed context about the failure cause including the card identifier,
 * requested status change, and specific validation failure reason. This enables precise error 
 * handling and appropriate HTTP 400 BAD REQUEST or 409 CONFLICT responses to REST API clients.
 * 
 * <p><b>COBOL Error Code Mapping:</b></p>
 * <ul>
 *   <li>COULD-NOT-LOCK-FOR-UPDATE (line 205) → CARD_LOCKED</li>
 *   <li>DATA-WAS-CHANGED-BEFORE-UPDATE (line 1511) → CONCURRENT_UPDATE_CONFLICT</li>
 *   <li>LOCKED-BUT-UPDATE-FAILED (line 1491) → UPDATE_FAILED</li>
 *   <li>CARD-EXPIRY-MONTH-NOT-VALID (line 197) → INVALID_EXPIRATION_DATE</li>
 *   <li>CARD-EXPIRY-YEAR-NOT-VALID (line 199) → INVALID_EXPIRATION_DATE</li>
 *   <li>CARD-STATUS-MUST-BE-YES-NO (line 195) → INVALID_STATUS_TRANSITION</li>
 *   <li>DID-NOT-FIND-ACCTCARD-COMBO (line 203) → CARD_NOT_FOUND</li>
 * </ul>
 * 
 * @see com.carddemo.service.CardUpdateService
 * @see com.carddemo.exception.GlobalExceptionHandler
 */
public class CardUpdateException extends RuntimeException {
    
    /**
     * Serial version UID for serialization compatibility.
     */
    private static final long serialVersionUID = 1L;
    
    /**
     * The card number associated with the failed update operation.
     * This field is optional and may be null for general update failures.
     * When present, it provides context for which specific card update failed.
     */
    private final String cardNumber;
    
    /**
     * The specific reason the card update operation failed.
     * This categorizes the failure to enable appropriate error handling and response codes.
     */
    private final UpdateFailureReason failureReason;
    
    /**
     * Enumeration of card update failure reasons.
     * 
     * This enum maps directly to error conditions from the COBOL COCRDUPC.cbl program
     * and provides standardized failure categories for exception handling.
     */
    public enum UpdateFailureReason {
        /**
         * Card not found in the database.
         * Maps to COBOL: DID-NOT-FIND-ACCTCARD-COMBO
         * HTTP Status: 404 NOT FOUND
         */
        CARD_NOT_FOUND,
        
        /**
         * Invalid status transition attempted.
         * Examples: Reactivating a stolen card, deactivating already inactive card.
         * Maps to COBOL: CARD-STATUS-MUST-BE-YES-NO
         * HTTP Status: 400 BAD REQUEST
         */
        INVALID_STATUS_TRANSITION,
        
        /**
         * Invalid expiration date specified.
         * Includes invalid month (not 1-12) or year (not 1950-2099).
         * Maps to COBOL: CARD-EXPIRY-MONTH-NOT-VALID, CARD-EXPIRY-YEAR-NOT-VALID
         * HTTP Status: 400 BAD REQUEST
         */
        INVALID_EXPIRATION_DATE,
        
        /**
         * Concurrent update conflict detected (optimistic locking failure).
         * Another transaction modified the card record between read and update.
         * Maps to COBOL: DATA-WAS-CHANGED-BEFORE-UPDATE (line 1511)
         * HTTP Status: 409 CONFLICT
         */
        CONCURRENT_UPDATE_CONFLICT,
        
        /**
         * Failed to acquire lock on card record for update.
         * Record is locked by another transaction.
         * Maps to COBOL: COULD-NOT-LOCK-FOR-UPDATE (line 205)
         * HTTP Status: 409 CONFLICT
         */
        CARD_LOCKED,
        
        /**
         * Update operation failed after successful lock acquisition.
         * Database write operation encountered an error.
         * Maps to COBOL: LOCKED-BUT-UPDATE-FAILED (line 1491)
         * HTTP Status: 500 INTERNAL SERVER ERROR
         */
        UPDATE_FAILED,
        
        /**
         * General validation error for card update fields.
         * Includes field format errors, constraint violations, and business rule violations.
         * HTTP Status: 400 BAD REQUEST
         */
        VALIDATION_ERROR,
        
        /**
         * Database-level error during update operation.
         * Includes constraint violations, connection failures, and persistence errors.
         * HTTP Status: 500 INTERNAL SERVER ERROR
         */
        DATABASE_ERROR
    }
    
    /**
     * Constructs a new CardUpdateException with the specified card number and failure reason.
     * 
     * This constructor is used when the card number is known but no additional message
     * or underlying cause is available. A default message is generated based on the failure reason.
     * 
     * @param cardNumber The card number that failed to update (may be null)
     * @param failureReason The specific reason for the update failure (must not be null)
     * @throws IllegalArgumentException if failureReason is null
     */
    public CardUpdateException(String cardNumber, UpdateFailureReason failureReason) {
        super(buildDefaultMessage(cardNumber, failureReason));
        if (failureReason == null) {
            throw new IllegalArgumentException("UpdateFailureReason must not be null");
        }
        this.cardNumber = cardNumber;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new CardUpdateException with a custom message, card number, and failure reason.
     * 
     * This constructor is used when a specific error message needs to be provided along with
     * the card number and failure reason. This is the most commonly used constructor for
     * business logic exceptions with detailed context.
     * 
     * @param message The detailed error message explaining the failure
     * @param cardNumber The card number that failed to update (may be null)
     * @param failureReason The specific reason for the update failure (must not be null)
     * @throws IllegalArgumentException if failureReason is null
     */
    public CardUpdateException(String message, String cardNumber, UpdateFailureReason failureReason) {
        super(message);
        if (failureReason == null) {
            throw new IllegalArgumentException("UpdateFailureReason must not be null");
        }
        this.cardNumber = cardNumber;
        this.failureReason = failureReason;
    }
    
    /**
     * Constructs a new CardUpdateException with a custom message and underlying cause.
     * 
     * This constructor is used when wrapping lower-level exceptions (e.g., database exceptions)
     * without specific card number or failure reason context. The failure reason defaults to
     * DATABASE_ERROR when using this constructor.
     * 
     * @param message The detailed error message explaining the failure
     * @param cause The underlying exception that caused this failure
     */
    public CardUpdateException(String message, Throwable cause) {
        super(message, cause);
        this.cardNumber = null;
        this.failureReason = UpdateFailureReason.DATABASE_ERROR;
    }
    
    /**
     * Constructs a new CardUpdateException with full context including message, cause, card number, and failure reason.
     * 
     * This constructor is used when wrapping lower-level exceptions while also providing
     * complete business context about the card update failure. This enables both technical
     * troubleshooting (via the cause) and business-level error handling (via failure reason).
     * 
     * @param message The detailed error message explaining the failure
     * @param cause The underlying exception that caused this failure
     * @param cardNumber The card number that failed to update (may be null)
     * @param failureReason The specific reason for the update failure (must not be null)
     * @throws IllegalArgumentException if failureReason is null
     */
    public CardUpdateException(String message, Throwable cause, String cardNumber, UpdateFailureReason failureReason) {
        super(message, cause);
        if (failureReason == null) {
            throw new IllegalArgumentException("UpdateFailureReason must not be null");
        }
        this.cardNumber = cardNumber;
        this.failureReason = failureReason;
    }
    
    /**
     * Returns the card number associated with the failed update operation.
     * 
     * @return The card number, or null if not available
     */
    public String getCardNumber() {
        return cardNumber;
    }
    
    /**
     * Returns the specific reason the card update operation failed.
     * 
     * This categorizes the failure to enable appropriate error handling,
     * HTTP status code selection, and user-facing error messages.
     * 
     * @return The update failure reason (never null)
     */
    public UpdateFailureReason getFailureReason() {
        return failureReason;
    }
    
    /**
     * Builds a default error message based on the card number and failure reason.
     * 
     * This method generates a consistent error message format when a custom message
     * is not provided. It includes the card number (masked for PCI compliance) and
     * a human-readable description of the failure reason.
     * 
     * @param cardNumber The card number that failed to update (may be null)
     * @param failureReason The specific reason for the update failure
     * @return A formatted error message
     */
    private static String buildDefaultMessage(String cardNumber, UpdateFailureReason failureReason) {
        if (failureReason == null) {
            return "Card update failed";
        }
        
        String maskedCardNumber = maskCardNumber(cardNumber);
        
        switch (failureReason) {
            case CARD_NOT_FOUND:
                return "Card not found: " + maskedCardNumber;
            case INVALID_STATUS_TRANSITION:
                return "Invalid status transition for card: " + maskedCardNumber;
            case INVALID_EXPIRATION_DATE:
                return "Invalid expiration date for card: " + maskedCardNumber;
            case CONCURRENT_UPDATE_CONFLICT:
                return "Concurrent update detected for card: " + maskedCardNumber + 
                       ". Record was modified by another transaction. Please review and retry.";
            case CARD_LOCKED:
                return "Could not lock card for update: " + maskedCardNumber + 
                       ". Record is currently locked by another transaction.";
            case UPDATE_FAILED:
                return "Update failed for card: " + maskedCardNumber;
            case VALIDATION_ERROR:
                return "Validation error for card update: " + maskedCardNumber;
            case DATABASE_ERROR:
                return "Database error during card update: " + maskedCardNumber;
            default:
                return "Card update failed: " + maskedCardNumber;
        }
    }
    
    /**
     * Masks the card number for PCI compliance.
     * 
     * Only the last 4 digits of the card number are shown, with the rest replaced by asterisks.
     * If the card number is null or invalid, returns a placeholder string.
     * 
     * @param cardNumber The full card number to mask
     * @return The masked card number (e.g., "************1234")
     */
    private static String maskCardNumber(String cardNumber) {
        if (cardNumber == null || cardNumber.isEmpty()) {
            return "[unknown]";
        }
        
        if (cardNumber.length() <= 4) {
            return "****";
        }
        
        String lastFour = cardNumber.substring(cardNumber.length() - 4);
        int maskedLength = cardNumber.length() - 4;
        StringBuilder masked = new StringBuilder();
        
        for (int i = 0; i < maskedLength; i++) {
            masked.append('*');
        }
        masked.append(lastFour);
        
        return masked.toString();
    }
}
