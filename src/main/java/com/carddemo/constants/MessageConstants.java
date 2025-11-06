/*
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
package com.carddemo.constants;

/**
 * Application message constants defining all user-facing messages, error messages,
 * validation messages, and system messages used across CardDemo application.
 * 
 * <p>This class replaces COBOL message copybook literals with Java String constants,
 * transforming messages from:</p>
 * <ul>
 *   <li>CSMSG01Y.cpy - Common application messages (CCDA-COMMON-MESSAGES)</li>
 *   <li>CSMSG02Y.cpy - ABEND error handling structure (ABEND-DATA)</li>
 * </ul>
 * 
 * <p>Messages are organized by functional category for maintainability and
 * to support future internationalization (i18n) requirements.</p>
 * 
 * <p><b>Design Notes:</b></p>
 * <ul>
 *   <li>Exact message text preserved from COBOL for functional equivalence</li>
 *   <li>Trailing spaces from COBOL PIC X(n) fields are trimmed</li>
 *   <li>Messages grouped by category: INFO, VALIDATION, ERROR, ABEND</li>
 *   <li>All constants are public static final for application-wide access</li>
 * </ul>
 * 
 * @version 1.0
 * @since 1.0
 */
public final class MessageConstants {

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private MessageConstants() {
        throw new UnsupportedOperationException("MessageConstants is a utility class and cannot be instantiated");
    }

    // ============================================================================
    // INFORMATIONAL MESSAGES
    // Messages providing user feedback for successful operations and general info
    // ============================================================================

    /**
     * Thank you message displayed to users.
     * <p>Source: CSMSG01Y.cpy - CCDA-MSG-THANK-YOU</p>
     * <p>Original COBOL: PIC X(50) VALUE 'Thank you for using CardDemo application...'</p>
     */
    public static final String MSG_THANK_YOU = "Thank you for using CardDemo application...";

    /**
     * Success message for generic operations.
     * <p>Used when an operation completes successfully without specific details needed.</p>
     */
    public static final String MSG_OPERATION_SUCCESS = "Operation completed successfully";

    /**
     * Success message for payment transactions.
     * <p>Displayed after a bill payment is successfully processed.</p>
     */
    public static final String MSG_PAYMENT_SUCCESS = "Payment processed successfully";

    /**
     * Success message for record creation.
     * <p>Displayed after a new record (user, account, card) is created.</p>
     */
    public static final String MSG_RECORD_CREATED_SUCCESS = "Record created successfully";

    /**
     * Success message for record updates.
     * <p>Displayed after an existing record is modified.</p>
     */
    public static final String MSG_RECORD_UPDATED_SUCCESS = "Record updated successfully";

    /**
     * Success message for record deletion.
     * <p>Displayed after a record is deleted from the system.</p>
     */
    public static final String MSG_RECORD_DELETED_SUCCESS = "Record deleted successfully";

    /**
     * Success message for transaction operations.
     * <p>Displayed after a card transaction is successfully posted.</p>
     */
    public static final String MSG_TRANSACTION_SUCCESS = "Transaction completed successfully";

    // ============================================================================
    // VALIDATION MESSAGES
    // Messages for user input validation and data integrity checks
    // ============================================================================

    /**
     * Invalid key press message.
     * <p>Source: CSMSG01Y.cpy - CCDA-MSG-INVALID-KEY</p>
     * <p>Original COBOL: PIC X(50) VALUE 'Invalid key pressed. Please see below...'</p>
     * <p>Displayed when user presses an invalid function key in BMS screens.</p>
     */
    public static final String MSG_INVALID_KEY = "Invalid key pressed. Please see below...";

    /**
     * Prompt for user ID entry.
     * <p>Displayed on login screen when user ID field is empty.</p>
     */
    public static final String MSG_PLEASE_ENTER_USER_ID = "Please enter your User ID";

    /**
     * Prompt for password entry.
     * <p>Displayed on login screen when password field is empty.</p>
     */
    public static final String MSG_PLEASE_ENTER_PASSWORD = "Please enter your Password";

    /**
     * Generic validation error message.
     * <p>Displayed when input validation fails.</p>
     */
    public static final String MSG_VALIDATION_ERROR = "Validation error occurred. Please check your input";

    /**
     * Required field missing message.
     * <p>Displayed when a mandatory field is left empty.</p>
     */
    public static final String MSG_REQUIRED_FIELD_MISSING = "Required field is missing";

    /**
     * Invalid input message.
     * <p>Displayed when input format or value is incorrect.</p>
     */
    public static final String MSG_INVALID_INPUT = "Invalid input provided";

    /**
     * Invalid payment amount message.
     * <p>Displayed when payment amount is zero, negative, or exceeds balance.</p>
     */
    public static final String MSG_INVALID_PAYMENT_AMOUNT = "Invalid payment amount";

    /**
     * Invalid menu option message.
     * <p>Displayed when user selects an invalid menu choice.</p>
     */
    public static final String MSG_INVALID_MENU_OPTION = "Invalid menu option selected";

    /**
     * Invalid card number message.
     * <p>Displayed when card number format is incorrect or fails validation.</p>
     */
    public static final String MSG_INVALID_CARD_NUMBER = "Invalid card number";

    // ============================================================================
    // ERROR MESSAGES - AUTHENTICATION & AUTHORIZATION
    // Messages related to user authentication and access control
    // ============================================================================

    /**
     * Wrong password error message.
     * <p>Displayed during login when password does not match.</p>
     */
    public static final String MSG_WRONG_PASSWORD = "Incorrect password. Please try again";

    /**
     * User not found error message.
     * <p>Displayed during login when user ID does not exist.</p>
     */
    public static final String MSG_USER_NOT_FOUND = "User not found";

    /**
     * Unable to verify user message.
     * <p>Displayed when user authentication process encounters an error.</p>
     */
    public static final String MSG_UNABLE_TO_VERIFY_USER = "Unable to verify user credentials";

    /**
     * Access denied message for administrative functions.
     * <p>Displayed when regular user attempts to access admin-only features.</p>
     */
    public static final String MSG_ACCESS_DENIED_ADMIN_ONLY = "Access denied. Administrative privileges required";

    // ============================================================================
    // ERROR MESSAGES - RESOURCE NOT FOUND
    // Messages when requested resources cannot be located
    // ============================================================================

    /**
     * Account not found error message.
     * <p>Displayed when account ID does not exist in the system.</p>
     */
    public static final String MSG_ACCOUNT_NOT_FOUND = "Account not found";

    /**
     * Card not found error message.
     * <p>Displayed when card number does not exist in the system.</p>
     */
    public static final String MSG_CARD_NOT_FOUND = "Card not found";

    /**
     * Transaction not found error message.
     * <p>Displayed when transaction ID does not exist in the system.</p>
     */
    public static final String MSG_TRANSACTION_NOT_FOUND = "Transaction not found";

    // ============================================================================
    // ERROR MESSAGES - BUSINESS LOGIC ERRORS
    // Messages for business rule violations
    // ============================================================================

    /**
     * Insufficient balance error message.
     * <p>Displayed when transaction amount exceeds available account balance.</p>
     */
    public static final String MSG_INSUFFICIENT_BALANCE = "Insufficient balance for this transaction";

    /**
     * Card expired error message.
     * <p>Displayed when attempting transaction with an expired card.</p>
     */
    public static final String MSG_CARD_EXPIRED = "Card has expired";

    // ============================================================================
    // ERROR MESSAGES - SYSTEM ERRORS
    // Messages for system-level and database errors
    // ============================================================================

    /**
     * Generic system error message.
     * <p>Displayed when an unexpected system error occurs.</p>
     */
    public static final String MSG_SYSTEM_ERROR = "System error occurred. Please contact support";

    /**
     * Database error message.
     * <p>Displayed when database operation fails.</p>
     */
    public static final String MSG_DATABASE_ERROR = "Database error occurred. Please try again later";

    // ============================================================================
    // ABEND ERROR HANDLING FIELDS
    // Field identifiers for ABEND (abnormal end) error structure
    // Source: CSMSG02Y.cpy - ABEND-DATA structure
    // ============================================================================

    /**
     * ABEND code field identifier.
     * <p>Source: CSMSG02Y.cpy - ABEND-CODE</p>
     * <p>Original COBOL: PIC X(4) - 4-character abend code</p>
     * <p>Used to identify the type of abnormal termination.</p>
     */
    public static final String ABEND_CODE_FIELD = "ABEND-CODE";

    /**
     * ABEND culprit field identifier.
     * <p>Source: CSMSG02Y.cpy - ABEND-CULPRIT</p>
     * <p>Original COBOL: PIC X(8) - 8-character program/module name</p>
     * <p>Identifies the program or module where the abend occurred.</p>
     */
    public static final String ABEND_CULPRIT_FIELD = "ABEND-CULPRIT";

    /**
     * ABEND reason field identifier.
     * <p>Source: CSMSG02Y.cpy - ABEND-REASON</p>
     * <p>Original COBOL: PIC X(50) - 50-character reason description</p>
     * <p>Provides detailed reason for the abnormal termination.</p>
     */
    public static final String ABEND_REASON_FIELD = "ABEND-REASON";

    /**
     * ABEND message field identifier.
     * <p>Source: CSMSG02Y.cpy - ABEND-MSG</p>
     * <p>Original COBOL: PIC X(72) - 72-character message text</p>
     * <p>Contains the full error message text for the abend.</p>
     */
    public static final String ABEND_MSG_FIELD = "ABEND-MSG";

    // ============================================================================
    // ABEND ERROR MESSAGE PATTERNS
    // Template patterns for constructing ABEND error messages
    // ============================================================================

    /**
     * ABEND message format pattern.
     * <p>Template for formatting complete ABEND error messages.</p>
     * <p>Usage: String.format(ABEND_MESSAGE_PATTERN, code, culprit, reason, message)</p>
     */
    public static final String ABEND_MESSAGE_PATTERN = 
        "ABEND %s occurred in %s: %s - %s";

    /**
     * ABEND log format pattern.
     * <p>Template for logging ABEND errors with all details.</p>
     * <p>Usage: String.format(ABEND_LOG_PATTERN, code, culprit, reason, message)</p>
     */
    public static final String ABEND_LOG_PATTERN = 
        "ABEND-CODE=%s, ABEND-CULPRIT=%s, ABEND-REASON=%s, ABEND-MSG=%s";
}
